#include "frames.h"

#include <windows.h>

#include <atomic>

#include "core/hooks.h"
#include "core/memory.h"
#include "core/process.h"
#include "core/ring.h"
#include "drawlist.h"
#include "overlay/overlay.h"
#include "protocol.h"

namespace kraken::frames {
namespace {

/**
 * The GL device fields the handshake names, from the mapping.
 */
struct Layout {
    uint32_t context = 0;   // GlDevice.context: present's argument is the device plus this
    uint32_t recording = 0; // GlDevice.recordingFrame: the frame object endFrame queues
    uint32_t rendering = 0; // GlDevice.renderingFrame: the frame object the render thread presents
};

// Recent frames whose tags the present can look up. The render queue holds two frames, so the frame
// being presented is always among the last few queued.
constexpr uint32_t TAGGED = 8;

// Offsets past this aren't GL device fields; a layout naming one is refused.
constexpr uint32_t MAX_OFFSET = 0x100000;

Layout g_layout;                   // written once, before g_ready
std::atomic<bool> g_ready{false};
void *g_trampoline = nullptr;
std::atomic<bool> g_hooked{false};

std::atomic<bool> g_sync{false};      // the last FRAME_SYNC asked for frame sync
std::atomic<uint32_t> g_requested{0}; // the last tag sent to Java to draw
std::atomic<uint32_t> g_done{0};      // the last tag Java reported done
std::atomic<uint32_t> g_released{0};  // the last tag the game thread stopped waiting for, done or not
HANDLE g_gameEvent = nullptr;         // wakes the game thread: a FRAME_DONE, or sync turning off
HANDLE g_presentEvent = nullptr;      // wakes the present thread: the same, or the game thread giving up

// The tags of the last TAGGED frames queued, by frame object, newest at g_count - 1. The game thread
// writes them; the present thread reads them. An entry's tag is 0 while it is being replaced.
std::atomic<uint64_t> g_frame[TAGGED];
std::atomic<uint32_t> g_tag[TAGGED];
std::atomic<uint32_t> g_count{0};

uint32_t g_nextTag = 0; // game thread only
int g_misses = 0;       // waits in a row that ran out; game thread only

/**
 * Whether a tag has reached another, allowing for the tags wrapping.
 *
 * @param tag  the tag
 * @param goal the tag it should have reached
 * @return true if tag is goal or later
 */
bool reached(uint32_t tag, uint32_t goal) {
    return static_cast<int32_t>(tag - goal) >= 0;
}

/**
 * Wakes both waiters, which then recheck what they wait for.
 */
void wakeWaiters() {
    if (g_gameEvent != nullptr) {
        SetEvent(g_gameEvent);
    }
    if (g_presentEvent != nullptr) {
        SetEvent(g_presentEvent);
    }
}

/**
 * Waits on an event until a condition holds, frame sync turns off, or FRAME_WAIT_MS pass.
 *
 * @param event the event that signals a change worth rechecking
 * @param ready the condition
 * @return true if the condition held (or sync turned off) in time
 */
template<typename Ready>
bool waitUntil(HANDLE event, Ready ready) {
    int64_t frequency = process::counterFrequency();
    LARGE_INTEGER start;
    QueryPerformanceCounter(&start);
    int64_t limit = frequency * FRAME_WAIT_MS / 1000;
    while (g_sync.load(std::memory_order_acquire) && !ready()) {
        LARGE_INTEGER now;
        QueryPerformanceCounter(&now);
        int64_t left = limit - (now.QuadPart - start.QuadPart);
        if (left <= 0) {
            return false;
        }
        WaitForSingleObject(event, static_cast<DWORD>((left * 1000 + frequency - 1) / frequency));
    }
    return true;
}

/**
 * Records the tag of a frame object about to be queued. Game thread.
 *
 * @param frame the frame object, 0 when the device has no render thread
 * @param tag   its tag
 */
void remember(uint64_t frame, uint32_t tag) {
    uint32_t n = g_count.load(std::memory_order_relaxed);
    uint32_t i = n % TAGGED;
    g_tag[i].store(0, std::memory_order_seq_cst);
    g_frame[i].store(frame, std::memory_order_seq_cst);
    g_tag[i].store(tag, std::memory_order_seq_cst);
    g_count.store(n + 1, std::memory_order_seq_cst);
}

/**
 * Holds the game thread until Java reports the overlay for a tag done, frame sync turns off, or
 * FRAME_WAIT_MS pass, then lets a present waiting for the same tag go on. After MAX_FRAME_MISSES waits
 * in a row run out, Java has fallen behind: it doesn't wait again until Java has done the frame before
 * this one. Game thread.
 *
 * @param tag the tag of the frame just queued
 */
void holdGame(uint32_t tag) {
    bool wait = true;
    if (g_misses >= MAX_FRAME_MISSES) {
        wait = reached(g_done.load(std::memory_order_acquire), tag - 1);
        if (wait) {
            g_misses = 0;
        }
    }
    if (wait) {
        if (waitUntil(g_gameEvent, [tag] { return reached(g_done.load(std::memory_order_acquire), tag); })) {
            g_misses = 0;
        } else {
            g_misses++;
        }
    }
    g_released.store(tag, std::memory_order_release);
    if (g_presentEvent != nullptr) {
        SetEvent(g_presentEvent);
    }
}

/**
 * The endFrame detour. First it resolves the draw list against the state the frame was recorded from,
 * keyed by the frame object being queued. While frame sync is on and the overlay draws a surface, it
 * then tags the frame and asks Java to draw its overlay, queues the frame (so the render thread starts
 * on it while Java draws), and holds the game thread, whose state Java is reading, until Java is done.
 * Game thread.
 *
 * @param device the GL device
 * @param task   endFrame's second argument, passed through
 */
void __fastcall endFrameDetour(void *device, void *task) {
    using Fn = void(__fastcall *)(void *, void *);
    uint64_t frame = 0;
    memory::readPointer(reinterpret_cast<uint64_t>(device) + g_layout.recording, &frame);
    drawlist::resolve(frame);
    if (!g_sync.load(std::memory_order_acquire) || !overlay::drawing()) {
        reinterpret_cast<Fn>(g_trampoline)(device, task);
        return;
    }
    uint32_t tag = ++g_nextTag;
    if (tag == 0) {
        tag = ++g_nextTag; // 0 means untagged
    }
    remember(frame, tag);
    g_requested.store(tag, std::memory_order_release);
    ring::wakeWriter();
    reinterpret_cast<Fn>(g_trampoline)(device, task);
    holdGame(tag);
}

} // namespace

bool init() {
    g_gameEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    g_presentEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    return g_gameEvent != nullptr && g_presentEvent != nullptr;
}

void install(FrameReader r) {
    if (g_ready.load(std::memory_order_acquire)) {
        return;
    }
    uint64_t endFrame = 0;
    Layout l;
    if (!r.get(endFrame) || !r.get(l.context) || !r.get(l.recording) || !r.get(l.rendering)) {
        return;
    }
    if (!process::inImage(endFrame) || l.context >= MAX_OFFSET || l.recording >= MAX_OFFSET
            || l.rendering >= MAX_OFFSET || l.recording == l.rendering) {
        return;
    }
    g_layout = l;
    g_ready.store(true, std::memory_order_release);
    hooks::installOnce(endFrame, reinterpret_cast<void *>(&endFrameDetour), &g_trampoline, g_hooked);
}

bool live() {
    return g_ready.load(std::memory_order_acquire) && g_hooked.load(std::memory_order_acquire) && overlay::hooked();
}

bool syncing() {
    return live() && g_sync.load(std::memory_order_acquire);
}

void setSync(FrameReader r) {
    uint8_t on = 0;
    if (!r.get(on)) {
        return;
    }
    g_sync.store(on != 0 && live(), std::memory_order_release);
    wakeWaiters();
}

void done(FrameReader r) {
    uint32_t frame = 0;
    if (!r.get(frame)) {
        return;
    }
    g_done.store(frame, std::memory_order_release);
    wakeWaiters();
}

void clear() {
    g_sync.store(false, std::memory_order_release);
    wakeWaiters();
}

uint32_t requested() {
    return g_requested.load(std::memory_order_acquire);
}

uint64_t presentingFrame(const void *context) {
    if (!g_ready.load(std::memory_order_acquire) || context == nullptr) {
        return 0;
    }
    uint64_t device = reinterpret_cast<uint64_t>(context) - g_layout.context;
    uint64_t frame = 0;
    memory::readPointer(device + g_layout.rendering, &frame);
    return frame;
}

uint32_t tagOf(const void *context) {
    if (!syncing()) {
        return 0;
    }
    uint64_t device = reinterpret_cast<uint64_t>(context) - g_layout.context;
    uint64_t frame = 0;
    if (!memory::readPointer(device + g_layout.rendering, &frame)) {
        return 0;
    }
    uint32_t n = g_count.load(std::memory_order_seq_cst);
    for (uint32_t back = 1; back <= TAGGED && back <= n; back++) {
        uint32_t i = (n - back) % TAGGED;
        uint32_t tag = g_tag[i].load(std::memory_order_seq_cst);
        uint64_t queued = g_frame[i].load(std::memory_order_seq_cst);
        if (tag != 0 && queued == frame && g_tag[i].load(std::memory_order_seq_cst) == tag) {
            return tag;
        }
    }
    return 0;
}

void awaitOverlay(uint32_t tag) {
    if (tag == 0 || !reached(g_requested.load(std::memory_order_acquire), tag)) {
        return; // untagged, or Java wasn't asked yet (a present inside endFrame, without a render thread)
    }
    waitUntil(g_presentEvent, [tag] {
        return reached(g_done.load(std::memory_order_acquire), tag)
               || reached(g_released.load(std::memory_order_acquire), tag);
    });
}

} // namespace kraken::frames
