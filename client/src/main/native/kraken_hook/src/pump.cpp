#include "pump.h"

#include <windows.h>

#include <atomic>

#include "calls.h"
#include "core/hooks.h"
#include "core/process.h"
#include "protocol.h"

#ifndef CREATE_WAITABLE_TIMER_HIGH_RESOLUTION
#define CREATE_WAITABLE_TIMER_HIGH_RESOLUTION 0x00000002
#endif

namespace kraken::pump {
namespace {

void *g_pumpTrampoline = nullptr;
std::atomic<bool> g_pumpHooked{false};

// Frame steps counted for FPS while the present hook isn't live.
std::atomic<uint64_t> g_frameSteps{0};

// The frame cap the pump holds the game to, frames a second, 0 for none. Set by FRAME_LIMIT and
// cleared when the connection drops. The event wakes a pump waiting out a frame when the cap
// changes, so lifting it takes effect at once.
std::atomic<uint32_t> g_frameLimit{0};
HANDLE g_limitChanged = nullptr;

/**
 * Waits on the game thread for a span of performance-counter ticks, or until the frame cap changes.
 * Uses a high-resolution waitable timer where Windows has one (10 1803 and later), a plain one
 * otherwise, and whole milliseconds if neither can be made.
 *
 * @param ticks how long to wait, in performance-counter ticks
 */
void waitTicks(int64_t ticks) {
    static HANDLE timer = [] {
        HANDLE t = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
        return t != nullptr ? t : CreateWaitableTimerExW(nullptr, nullptr, 0, TIMER_ALL_ACCESS);
    }();
    int64_t frequency = process::counterFrequency();
    LARGE_INTEGER due;
    due.QuadPart = -(ticks * 10000000 / frequency); // relative, in 100 ns units
    if (timer != nullptr && due.QuadPart < 0 && SetWaitableTimer(timer, &due, 0, nullptr, nullptr, FALSE)) {
        HANDLE handles[2] = {timer, g_limitChanged};
        if (WaitForMultipleObjects(2, handles, FALSE, INFINITE) != WAIT_OBJECT_0) {
            CancelWaitableTimer(timer);
        }
        return;
    }
    DWORD ms = static_cast<DWORD>(ticks * 1000 / frequency);
    if (ms > 0) {
        WaitForSingleObject(g_limitChanged, ms);
    }
}

/**
 * Holds the game to the frame cap: each frame step may start one interval after the last, and waits
 * out the rest of it. A step that comes more than an interval late starts the schedule over rather
 * than letting the following ones run back to back to catch up. The client times its own logic
 * cycles, so a held frame runs more of them rather than slowing the game. Game thread only.
 */
void paceFrame() {
    static int64_t due = 0; // performance counter when the next step may start, 0 while uncapped
    uint32_t fps = g_frameLimit.load(std::memory_order_relaxed);
    int64_t frequency = process::counterFrequency();
    if (fps == 0 || frequency == 0) {
        due = 0;
        return;
    }
    int64_t interval = frequency / fps;
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    if (due == 0 || now.QuadPart - due > interval) {
        due = now.QuadPart + interval;
        return;
    }
    if (now.QuadPart < due) {
        waitTicks(due - now.QuadPart);
    }
    due += interval;
}

/**
 * The pump detour on the client's per-frame step: holds the frame to the cap, then runs the queued
 * calls on the game thread, before the frame's logic and rendering, then forwards to the original.
 *
 * @param self the app object
 * @param dt   the frame's float argument, passed through in XMM1
 */
void __fastcall pumpDetour(void *self, float dt) {
    paceFrame();
    g_frameSteps.fetch_add(1, std::memory_order_relaxed);
    calls::runPending();
    using Fn = void(__fastcall *)(void *, float);
    reinterpret_cast<Fn>(g_pumpTrampoline)(self, dt);
}

} // namespace

bool init() {
    g_limitChanged = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    return g_limitChanged != nullptr;
}

void install(uint64_t rva) {
    hooks::installOnce(rva, reinterpret_cast<void *>(&pumpDetour), &g_pumpTrampoline, g_pumpHooked);
}

bool hooked() {
    return g_pumpHooked.load(std::memory_order_acquire);
}

void setFrameLimit(FrameReader r) {
    uint32_t fps = 0;
    if (!r.get(fps)) {
        return;
    }
    g_frameLimit.store(fps > MAX_FRAME_LIMIT ? 0 : fps, std::memory_order_relaxed);
    SetEvent(g_limitChanged);
}

void liftFrameLimit() {
    g_frameLimit.store(0, std::memory_order_relaxed);
    SetEvent(g_limitChanged);
}

uint32_t frameLimit() {
    return g_frameLimit.load(std::memory_order_relaxed);
}

uint64_t frameSteps() {
    return g_frameSteps.load(std::memory_order_relaxed);
}

} // namespace kraken::pump
