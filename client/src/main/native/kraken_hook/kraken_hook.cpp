// kraken_hook.dll - injected into osclient.exe. It turns the client's own event raises into push
// events for the Kraken client, runs the client functions the Kraken client asks for on the game
// thread, and draws the Kraken client's overlay inside the game's own OpenGL frame.
//
// On load it connects back to the Kraken client over a loopback socket and receives a handshake: a
// table of raise functions to hook, each with the event id it carries (fixed, or read from an
// argument register) and a capture plan saying which arguments to forward and how to read them,
// then the Client* slot, the per-frame pump function and the present function. It inline-hooks
// each raiser with a generic detour. When the client calls a hooked raiser the detour captures the
// id and the planned values, pushes a message into a lock-free ring, and returns through the
// original; a writer thread drains the ring to the socket.
//
// After the handshake the Kraken client can send commands. A reader thread queues call batches,
// which the pump detour runs on the game thread at the start of the next frame (each batch with a
// scratch block its calls can share, so a sequence of calls can build one object), and opens the
// overlay surface (a file mapping holding three frames), which the present detour uploads and draws
// over the finished frame just before the client presents it. A frame cap makes the pump hold each
// frame back until it is due. The writer thread reports the game's frame rate once a second.
//
// Nothing here is hard-coded per client build: every RVA, event id, capture plan, function to call
// and argument comes from the mapping on the Java side. See README.md, docs/event-hooks-design.md
// and docs/in-game-drawing.md.
//
// Why a socket and not a JNI/JNA callback: this DLL runs inside osclient.exe, which has no JVM, so a
// Java callback pointer means nothing in this address space. The socket is the bridge.

// winsock2.h must come before windows.h, and WIN32_LEAN_AND_MEAN stops windows.h from pulling in the
// old Winsock 1 winsock.h, which would clash with winsock2.h.
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <GL/gl.h>
#include <cstdint>
#include <cstring>
#include <cstddef>
#include <algorithm>
#include <atomic>
#include <utility>
#include <vector>
#include "MinHook.h"

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "opengl32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "user32.lib")

// Must match NativeEventServer.PORT on the Java side.
static const unsigned short KRAKEN_PORT = 42069;

// Protocol v4 limits and op codes. Must match NativeProtocol on the Java side.
static const uint32_t PROTOCOL_VERSION = 4;
static const int MAX_SLOTS = 64;   // distinct raise functions we can hook
static const int MAX_PLAN = 8;     // capture-plan entries per slot
static const int MAX_MSG = 512;    // bytes per queued message body
static const int RING_SIZE = 1024; // queued messages before the oldest is dropped (power of two)
static const int RING_MASK = RING_SIZE - 1;
static const int CSTR_MAX = 64;    // longest NUL-terminated string captured

// Capture-plan ops: how to read the argument register named by the plan entry.
static const uint8_t OP_RAW = 0;   // the register value itself (u64)
static const uint8_t OP_I32 = 1;   // int32 at [register]
static const uint8_t OP_I64 = 2;   // int64 at [register]
static const uint8_t OP_I32X2 = 3; // two int32 at [register]
static const uint8_t OP_CSTR = 4;  // NUL-terminated bytes at [register], at most CSTR_MAX

// Reserved message ids. STATUS: regs = {installed slots, requested slots, features, protocol}.
// FRAME: regs = {framebuffer width, height, GL version (major << 16 | minor), surface generation}.
// CALLS_DONE: regs = {batch id, calls run, calls faulted or skipped, last return value}.
// FPS: regs = {frames since the last FPS, microseconds since it, frame cap in force, 1 if presents
// were counted (0: frame steps)}.
static const int32_t STATUS_EVENT_ID = -2;
static const int32_t FRAME_EVENT_ID = -3;
static const int32_t CALLS_DONE_EVENT_ID = -4;
static const int32_t FPS_EVENT_ID = -5;

// STATUS feature bits. FRAME_RATE: FPS is sent, and FRAME_LIMIT is honoured while the pump is hooked.
static const uint64_t FEATURE_PUMP = 1;
static const uint64_t FEATURE_PRESENT = 2;
static const uint64_t FEATURE_FRAME_RATE = 4;

// Command frames (Java -> DLL): {u32 len, u8 type, body}, len counting the type byte and the body.
static const uint8_t CMD_CALLS = 1;
static const uint8_t CMD_SURFACE = 2;
static const uint8_t CMD_FRAME_LIMIT = 3;
static const uint32_t MAX_FRAME_LIMIT = 1000; // a higher cap is no cap
static const uint32_t MAX_FRAME = 4u << 20;   // bytes per command frame
static const int MAX_ARGS = 8;                // arguments per call
static const uint32_t MAX_PENDING_CALLS = 4096;
static const uint32_t MAX_PENDING_BATCHES = 4096;

// Call argument kinds.
static const uint8_t ARG_IMM = 0;      // the value
static const uint8_t ARG_CLIENT = 1;   // the Client* read from base + clientGlobal, plus the value
static const uint8_t ARG_DATA = 2;     // a pointer to data + value
static const uint8_t ARG_SCRATCH = 3;  // a pointer to the batch's scratch block + value
static const uint8_t ARG_INDIRECT = 4; // the pointer at Client* + (value & 0xffffffff), plus (value >> 32)
static const uint32_t SCRATCH_SIZE = 4096; // bytes of zeroed scratch a batch's calls share

// Overlay surface layout (docs/in-game-drawing.md section 2.4).
static const uint32_t SURFACE_MAGIC = 0x31564F4B; // "KOV1"
static const size_t SURFACE_PUBLISHED = 16;
static const size_t SURFACE_READING = 20;
static const size_t SURFACE_SLOT_HEADERS = 64;
static const size_t SURFACE_SLOT_HEADER_SIZE = 16;
static const size_t SURFACE_PIXELS = 128;
static const uint32_t SURFACE_MAX_SLOTS = 3;
static const uint32_t SURFACE_MAX_SIDE = 16384;
static const uint32_t PUBLISHED_NONE = 0xffffffffu;
static const uint32_t PUBLISHED_UNSEEN = 0xfffffffeu; // forces a full upload of whatever is published

// SEH guards the pointer dereferences a capture plan does and every call into the client: a bad
// pointer or a faulting function must raise rather than crash the client. MSVC (the build's
// preferred toolchain) supports it directly; under MinGW these run unguarded.
#if defined(_MSC_VER)
#define GUARD_BEGIN __try {
#define GUARD_END } __except (EXCEPTION_EXECUTE_HANDLER) { faulted = true; }
#else
#define GUARD_BEGIN {
#define GUARD_END }
#endif

/**
 * One capture-plan entry: read argument register {@code arg} (0=RCX..3=R9) with op {@code op}.
 */
struct PlanEntry {
    uint8_t arg;
    uint8_t op;
};

/**
 * One hooked raise function: where it is, how to find the event id it carries, how many argument
 * registers to forward and the plan for reading payload out of them.
 */
struct Slot {
    void *target = nullptr;     // module base + rva
    void *trampoline = nullptr; // the original, through MinHook
    uint64_t rva = 0;
    int32_t fixedId = -1;       // the event id this raiser always carries, or -1 to read it from idArg
    uint8_t idArg = 0;          // register holding the id when fixedId < 0
    uint8_t args = 0;           // registers to forward (0..4)
    uint8_t planLen = 0;
    PlanEntry plan[MAX_PLAN];
};

/**
 * One queued message: {@code len} body bytes in {@code data}, ready to frame and send.
 */
struct Msg {
    uint32_t len = 0;
    uint8_t data[MAX_MSG];
};

/**
 * One client function call from a CALLS command: the function, its arguments and the data block
 * DATA arguments point into. The data is kept 16 bytes larger than asked, so an aligned start fits.
 */
struct Call {
    uint64_t rva = 0;
    uint8_t argc = 0;
    uint8_t kinds[MAX_ARGS] = {};
    int64_t values[MAX_ARGS] = {};
    uint32_t dataLen = 0;
    std::vector<uint8_t> storage;
};

/**
 * One CALLS command: the calls to run in order, plus a count of calls refused before they were
 * queued (a malformed frame or a full queue), which CALLS_DONE reports as skipped.
 */
struct Batch {
    uint32_t id = 0;
    uint32_t skipped = 0;
    std::vector<Call> calls;
};

/**
 * One opened overlay surface: the mapping, its view and the header values checked when it opened.
 * A view of nullptr is a request to stop drawing.
 */
struct SurfaceView {
    HANDLE mapping = nullptr;
    uint8_t *view = nullptr;
    uint32_t generation = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t slots = 0;
};

static SOCKET g_sock = INVALID_SOCKET;
static Slot g_slots[MAX_SLOTS];
static std::atomic<int> g_slotCount{0};
static uintptr_t g_base = 0;
static uint64_t g_imageSize = 0;

static Msg g_ring[RING_SIZE];
static std::atomic<uint32_t> g_head{0}; // consumer: next message to send
static std::atomic<uint32_t> g_tail{0}; // producer: next free slot
static std::atomic<uint32_t> g_seq{0};  // per-message sequence, so Java sees ring drops as gaps
static HANDLE g_dataEvent = nullptr;    // signalled when the ring gains a message or the frame changes
static std::atomic<bool> g_connBroken{false}; // set by the reader thread when the socket closes

// One generic detour per possible slot; filled with &detourK<0..MAX_SLOTS-1>. MinHook needs a
// distinct function per target, and every raiser shares one four-register fastcall shape.
static void *g_detours[MAX_SLOTS];

// The Client* slot, the pump and the present hooks. The hooks install once, from the first
// handshake that names them.
static std::atomic<uint64_t> g_clientGlobal{0};
static void *g_pumpTrampoline = nullptr;
static std::atomic<bool> g_pumpHooked{false};
static void *g_presentTrampoline = nullptr;
static std::atomic<bool> g_presentHooked{false};

// The call queue: filled by the reader thread, drained by the pump on the game thread.
static SRWLOCK g_queueLock = SRWLOCK_INIT;
static std::vector<Batch> g_queue;
static std::atomic<uint32_t> g_pendingBatches{0};
static uint32_t g_pendingCalls = 0; // guarded by g_queueLock

// The surface the reader thread opened (or a stop request), waiting for the present thread.
static std::atomic<SurfaceView *> g_pendingSurface{nullptr};

// What the present thread last saw, for FRAME. Written by the present thread, sent by the writer.
static std::atomic<uint32_t> g_fbWidth{0};
static std::atomic<uint32_t> g_fbHeight{0};
static std::atomic<uint32_t> g_glVersion{0};
static std::atomic<uint32_t> g_drawnGeneration{0};

// Frames counted for FPS: presents while the present hook is live, frame steps otherwise.
static std::atomic<uint64_t> g_presents{0};
static std::atomic<uint64_t> g_frameSteps{0};

// The frame cap the pump holds the game to, frames a second, 0 for none. Set by FRAME_LIMIT and
// cleared when the connection drops, so a closed Kraken client never leaves the game capped. The
// event wakes a pump waiting out a frame when the cap changes, so lifting it takes effect at once.
static std::atomic<uint32_t> g_frameLimit{0};
static HANDLE g_limitChanged = nullptr;
static int64_t g_qpcFrequency = 0;

static void installSlot(Slot slot);
static void installPump(uint64_t rva);
static void installPresent(uint64_t rva);
static void sendStatus(uint16_t requested);
static void requestSurface(SurfaceView *surface);

/**
 * Appends one capture-plan value to a message body, dereferencing the argument register per the op.
 * Dereferences are SEH-guarded, so a bad pointer drops the value instead of crashing the client.
 *
 * @param out   the message body to append to
 * @param off   the current write offset, advanced past the value written
 * @param entry the plan entry (which register, which op)
 * @param regs  the four captured argument registers
 */
static void appendValue(uint8_t *out, int &off, const PlanEntry &entry, const uint64_t regs[4]) {
    uint64_t reg = regs[entry.arg & 3];
    out[off++] = entry.arg;
    out[off++] = entry.op;
    bool faulted = false;
    switch (entry.op) {
        case OP_RAW: {
            std::memcpy(out + off, &reg, 8);
            off += 8;
            break;
        }
        case OP_I32: {
            int32_t v = 0;
            GUARD_BEGIN v = *reinterpret_cast<const int32_t *>(reg); GUARD_END
            std::memcpy(out + off, &v, 4);
            off += 4;
            break;
        }
        case OP_I64: {
            int64_t v = 0;
            GUARD_BEGIN v = *reinterpret_cast<const int64_t *>(reg); GUARD_END
            std::memcpy(out + off, &v, 8);
            off += 8;
            break;
        }
        case OP_I32X2: {
            int32_t v[2] = {0, 0};
            GUARD_BEGIN
                v[0] = reinterpret_cast<const int32_t *>(reg)[0];
                v[1] = reinterpret_cast<const int32_t *>(reg)[1];
            GUARD_END
            std::memcpy(out + off, v, 8);
            off += 8;
            break;
        }
        case OP_CSTR: {
            uint8_t buf[CSTR_MAX];
            uint8_t n = 0;
            GUARD_BEGIN
                const char *s = reinterpret_cast<const char *>(reg);
                while (n < CSTR_MAX && s[n] != 0) {
                    buf[n] = static_cast<uint8_t>(s[n]);
                    n++;
                }
            GUARD_END
            out[off++] = n;
            std::memcpy(out + off, buf, n);
            off += n;
            break;
        }
        default:
            // Unknown op: emit an empty value so the frame stays parseable.
            break;
    }
    (void) faulted;
}

/**
 * Pushes one finished message body into the ring. Only the game thread may call this: the ring has
 * a single producer. A full ring drops the message; its sequence number was already taken, so Java
 * sees the gap.
 *
 * @param body the message body, starting with its sequence number
 * @param len  the body length in bytes
 */
static void pushRing(const uint8_t *body, int len) {
    uint32_t tail = g_tail.load(std::memory_order_relaxed);
    uint32_t head = g_head.load(std::memory_order_acquire);
    if (tail - head >= RING_SIZE) {
        return; // ring full: drop (the sequence gap tells Java)
    }
    Msg &msg = g_ring[tail & RING_MASK];
    msg.len = static_cast<uint32_t>(len);
    std::memcpy(msg.data, body, len);
    g_tail.store(tail + 1, std::memory_order_release);
    if (g_dataEvent) {
        SetEvent(g_dataEvent);
    }
}

/**
 * Writes the fixed message head (sequence, id, four registers) into a body.
 *
 * @param body where to write; at least 40 bytes
 * @param id   the event or reserved id
 * @param regs the four register values
 * @return the offset just past the head
 */
static int writeHead(uint8_t *body, int32_t id, const uint64_t regs[4]) {
    uint32_t seq = g_seq.fetch_add(1, std::memory_order_relaxed);
    int off = 0;
    std::memcpy(body + off, &seq, 4); off += 4;
    std::memcpy(body + off, &id, 4); off += 4;
    std::memcpy(body + off, regs, 32); off += 32;
    return off;
}

/**
 * Builds a message for one raise and pushes it into the ring. Runs on the game thread, so it only
 * captures and enqueues; the writer thread does the socket work.
 *
 * @param slot the hooked raiser
 * @param id   the resolved event id
 * @param regs the four captured argument registers
 */
static void enqueue(const Slot &slot, int32_t id, const uint64_t regs[4]) {
    uint8_t body[MAX_MSG];
    int off = writeHead(body, id, regs);
    int countOff = off++;
    uint8_t nValues = 0;
    for (int i = 0; i < slot.planLen; i++) {
        if (off + 2 + 8 + CSTR_MAX > MAX_MSG) {
            break; // never overrun the body; the remaining values are dropped
        }
        appendValue(body, off, slot.plan[i], regs);
        nValues++;
    }
    body[countOff] = nValues;
    pushRing(body, off);
}

/**
 * The body of every generic detour. Resolves the event id, captures the plan, enqueues a message,
 * then forwards to the original through the trampoline and returns its result unchanged.
 *
 * @param k the slot index this detour stands in for
 * @param a RCX at the call
 * @param b RDX at the call
 * @param c R8 at the call
 * @param d R9 at the call
 * @return whatever the original raiser returns
 */
static int64_t handle(int k, int64_t a, int64_t b, int64_t c, int64_t d) {
    Slot &slot = g_slots[k];
    uint64_t regs[4] = {
        static_cast<uint64_t>(a), static_cast<uint64_t>(b),
        static_cast<uint64_t>(c), static_cast<uint64_t>(d)
    };
    int32_t id = slot.fixedId;
    if (id < 0) {
        id = static_cast<int32_t>(regs[slot.idArg & 3]);
    }
    enqueue(slot, id, regs);
    using Fn = int64_t(__fastcall *)(int64_t, int64_t, int64_t, int64_t);
    return reinterpret_cast<Fn>(slot.trampoline)(a, b, c, d);
}

template<int K>
static int64_t __fastcall detourK(int64_t a, int64_t b, int64_t c, int64_t d) {
    return handle(K, a, b, c, d);
}

template<std::size_t... I>
static void fillDetours(std::index_sequence<I...>) {
    ((g_detours[I] = reinterpret_cast<void *>(&detourK<I>)), ...);
}

/**
 * Reads the Client* the handshake's clientGlobal slot holds, guarding the read.
 *
 * @return the Client*, or 0 if the slot is unknown, unreadable or null
 */
static uint64_t readClient() {
    uint64_t slot = g_clientGlobal.load(std::memory_order_relaxed);
    if (slot == 0 || slot >= g_imageSize) {
        return 0;
    }
    uint64_t client = 0;
    bool faulted = false;
    GUARD_BEGIN client = *reinterpret_cast<const volatile uint64_t *>(g_base + slot); GUARD_END
    return faulted ? 0 : client;
}

/**
 * Calls one client function with eight integer arguments under an SEH guard. Functions that take
 * fewer ignore the rest: the Microsoft x64 caller owns the stack slots.
 *
 * @param target the function's address
 * @param args   the eight argument values
 * @param ret    receives the return value (RAX)
 * @return false if the call faulted
 */
static bool guardedCall(uintptr_t target, const int64_t args[MAX_ARGS], int64_t *ret) {
    using Fn = int64_t(__fastcall *)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t);
    Fn fn = reinterpret_cast<Fn>(target);
    bool faulted = false;
    GUARD_BEGIN
        *ret = fn(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
    GUARD_END
    return !faulted;
}

/**
 * Reads a pointer-sized value from the game's memory, guarding the read.
 *
 * @param address where to read
 * @param out     receives the value
 * @return false if the read faulted
 */
static bool readPointer(uint64_t address, uint64_t *out) {
    bool faulted = false;
    uint64_t value = 0;
    GUARD_BEGIN value = *reinterpret_cast<const volatile uint64_t *>(address); GUARD_END
    *out = value;
    return !faulted;
}

/**
 * Resolves one call's arguments and runs it.
 *
 * @param call    the call
 * @param scratch the batch's scratch block, SCRATCH_SIZE bytes, 16-byte aligned
 * @param ret     receives the return value when it ran
 * @return 1 if it ran, 0 if it was skipped (bad target, no Client*, null pointer, bad offset), -1 if it faulted
 */
static int runCall(Call &call, uint8_t *scratch, int64_t *ret) {
    if (call.rva == 0 || call.rva >= g_imageSize || call.argc > MAX_ARGS) {
        return 0;
    }
    uint8_t *data = nullptr;
    if (!call.storage.empty()) {
        uintptr_t raw = reinterpret_cast<uintptr_t>(call.storage.data());
        data = reinterpret_cast<uint8_t *>((raw + 15) & ~static_cast<uintptr_t>(15));
    }
    int64_t args[MAX_ARGS] = {};
    for (int i = 0; i < call.argc; i++) {
        switch (call.kinds[i]) {
            case ARG_IMM:
                args[i] = call.values[i];
                break;
            case ARG_CLIENT: {
                uint64_t client = readClient();
                if (client == 0) {
                    return 0;
                }
                args[i] = static_cast<int64_t>(client + static_cast<uint64_t>(call.values[i]));
                break;
            }
            case ARG_DATA:
                if (data == nullptr || call.values[i] < 0 || static_cast<uint64_t>(call.values[i]) > call.dataLen) {
                    return 0;
                }
                args[i] = static_cast<int64_t>(reinterpret_cast<uintptr_t>(data + call.values[i]));
                break;
            case ARG_SCRATCH:
                if (call.values[i] < 0 || static_cast<uint64_t>(call.values[i]) >= SCRATCH_SIZE) {
                    return 0;
                }
                args[i] = static_cast<int64_t>(reinterpret_cast<uintptr_t>(scratch + call.values[i]));
                break;
            case ARG_INDIRECT: {
                uint64_t client = readClient();
                if (client == 0) {
                    return 0;
                }
                uint64_t offset = static_cast<uint64_t>(call.values[i]) & 0xffffffffu;
                int64_t add = call.values[i] >> 32;
                uint64_t pointer = 0;
                if (!readPointer(client + offset, &pointer) || pointer == 0) {
                    return 0;
                }
                args[i] = static_cast<int64_t>(pointer + static_cast<uint64_t>(add));
                break;
            }
            default:
                return 0;
        }
    }
    return guardedCall(g_base + call.rva, args, ret) ? 1 : -1;
}

/**
 * Runs every queued call batch, in order, and reports each with CALLS_DONE. Runs on the game thread
 * in the pump detour. Takes the queue with a try-lock, so a reader thread that holds it costs one
 * frame's delay rather than a stall. Each batch gets a fresh, zeroed scratch block its calls can
 * build an object in and hand on to each other.
 */
static void runPendingCalls() {
    if (g_pendingBatches.load(std::memory_order_acquire) == 0) {
        return;
    }
    std::vector<Batch> work;
    if (!TryAcquireSRWLockExclusive(&g_queueLock)) {
        return;
    }
    work.swap(g_queue);
    g_pendingCalls = 0;
    g_pendingBatches.store(0, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_queueLock);

    std::vector<uint8_t> scratchStorage(SCRATCH_SIZE + 16);
    for (Batch &batch : work) {
        uint64_t run = 0;
        uint64_t failed = batch.skipped;
        int64_t last = 0;
        std::fill(scratchStorage.begin(), scratchStorage.end(), 0);
        uintptr_t raw = reinterpret_cast<uintptr_t>(scratchStorage.data());
        uint8_t *scratch = reinterpret_cast<uint8_t *>((raw + 15) & ~static_cast<uintptr_t>(15));
        for (Call &call : batch.calls) {
            int64_t ret = 0;
            int outcome = runCall(call, scratch, &ret);
            if (outcome > 0) {
                run++;
                last = ret;
            } else {
                failed++;
            }
        }
        uint64_t regs[4] = {batch.id, run, failed, static_cast<uint64_t>(last)};
        uint8_t body[48];
        int off = writeHead(body, CALLS_DONE_EVENT_ID, regs);
        body[off++] = 0;
        pushRing(body, off);
    }
}

#ifndef CREATE_WAITABLE_TIMER_HIGH_RESOLUTION
#define CREATE_WAITABLE_TIMER_HIGH_RESOLUTION 0x00000002
#endif

/**
 * Waits on the game thread for a span of performance-counter ticks, or until the frame cap changes.
 * Uses a high-resolution waitable timer where Windows has one (10 1803 and later), a plain one
 * otherwise, and whole milliseconds if neither can be made.
 *
 * @param ticks how long to wait, in performance-counter ticks
 */
static void waitTicks(int64_t ticks) {
    static HANDLE timer = [] {
        HANDLE t = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
        return t != nullptr ? t : CreateWaitableTimerExW(nullptr, nullptr, 0, TIMER_ALL_ACCESS);
    }();
    LARGE_INTEGER due;
    due.QuadPart = -(ticks * 10000000 / g_qpcFrequency); // relative, in 100 ns units
    if (timer != nullptr && due.QuadPart < 0 && SetWaitableTimer(timer, &due, 0, nullptr, nullptr, FALSE)) {
        HANDLE handles[2] = {timer, g_limitChanged};
        if (WaitForMultipleObjects(2, handles, FALSE, INFINITE) != WAIT_OBJECT_0) {
            CancelWaitableTimer(timer);
        }
        return;
    }
    DWORD ms = static_cast<DWORD>(ticks * 1000 / g_qpcFrequency);
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
static void paceFrame() {
    static int64_t due = 0; // performance counter when the next step may start, 0 while uncapped
    uint32_t fps = g_frameLimit.load(std::memory_order_relaxed);
    if (fps == 0 || g_qpcFrequency == 0) {
        due = 0;
        return;
    }
    int64_t interval = g_qpcFrequency / fps;
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
static void __fastcall pumpDetour(void *self, float dt) {
    paceFrame();
    g_frameSteps.fetch_add(1, std::memory_order_relaxed);
    runPendingCalls();
    using Fn = void(__fastcall *)(void *, float);
    reinterpret_cast<Fn>(g_pumpTrampoline)(self, dt);
}

/**
 * Drops every queued call, as when the connection that sent them closes.
 */
static void clearPendingCalls() {
    std::vector<Batch> dropped;
    AcquireSRWLockExclusive(&g_queueLock);
    dropped.swap(g_queue);
    g_pendingCalls = 0;
    g_pendingBatches.store(0, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_queueLock);
}

/**
 * A bounds-checked little-endian reader over one command frame.
 */
struct FrameReader {
    const uint8_t *p;
    size_t left;

    template<typename T>
    bool get(T &out) {
        if (left < sizeof(T)) {
            return false;
        }
        std::memcpy(&out, p, sizeof(T));
        p += sizeof(T);
        left -= sizeof(T);
        return true;
    }

    bool bytes(uint8_t *out, size_t n) {
        if (left < n) {
            return false;
        }
        if (n > 0) {
            std::memcpy(out, p, n);
        }
        p += n;
        left -= n;
        return true;
    }
};

/**
 * Parses a CALLS command and queues it for the pump. A malformed frame or a full queue still
 * queues the batch, with every call counted as skipped, so CALLS_DONE answers it. Nothing is queued
 * while no pump is hooked: nothing would run it.
 *
 * @param r the frame's body after the type byte
 */
static void queueCalls(FrameReader r) {
    if (!g_pumpHooked.load(std::memory_order_acquire)) {
        return;
    }
    Batch batch;
    uint16_t count = 0;
    if (!r.get(batch.id) || !r.get(count)) {
        return;
    }
    bool ok = true;
    // A call takes at least 11 bytes, so a count the frame can't hold reserves no more than it could
    batch.calls.reserve(count < r.left / 11 ? count : r.left / 11);
    for (uint16_t i = 0; i < count && ok; i++) {
        Call call;
        ok = r.get(call.rva) && r.get(call.argc) && call.argc <= MAX_ARGS;
        for (uint8_t a = 0; ok && a < call.argc; a++) {
            ok = r.get(call.kinds[a]) && r.get(call.values[a]);
        }
        uint16_t dataLen = 0;
        ok = ok && r.get(dataLen);
        if (ok && dataLen > 0) {
            call.dataLen = dataLen;
            call.storage.assign(static_cast<size_t>(dataLen) + 16, 0);
            uintptr_t raw = reinterpret_cast<uintptr_t>(call.storage.data());
            uint8_t *aligned = reinterpret_cast<uint8_t *>((raw + 15) & ~static_cast<uintptr_t>(15));
            ok = r.bytes(aligned, dataLen);
        }
        if (ok) {
            batch.calls.push_back(std::move(call));
        }
    }
    if (!ok) {
        batch.calls.clear();
        batch.skipped = count;
    }
    AcquireSRWLockExclusive(&g_queueLock);
    if (g_queue.size() < MAX_PENDING_BATCHES) {
        if (g_pendingCalls + batch.calls.size() > MAX_PENDING_CALLS) {
            batch.skipped += static_cast<uint32_t>(batch.calls.size());
            batch.calls.clear();
        }
        g_pendingCalls += static_cast<uint32_t>(batch.calls.size());
        g_queue.push_back(std::move(batch));
        g_pendingBatches.store(static_cast<uint32_t>(g_queue.size()), std::memory_order_release);
    }
    ReleaseSRWLockExclusive(&g_queueLock);
}

/**
 * Unmaps and frees a surface.
 *
 * @param surface the surface, or nullptr
 */
static void freeSurface(SurfaceView *surface) {
    if (surface == nullptr) {
        return;
    }
    if (surface->view != nullptr) {
        UnmapViewOfFile(surface->view);
    }
    if (surface->mapping != nullptr) {
        CloseHandle(surface->mapping);
    }
    delete surface;
}

/**
 * Hands a surface (or a stop request) to the present thread, freeing one it never took.
 *
 * @param surface the opened surface, or a SurfaceView with no view to stop drawing
 */
static void requestSurface(SurfaceView *surface) {
    SurfaceView *old = g_pendingSurface.exchange(surface, std::memory_order_acq_rel);
    freeSurface(old);
}

/**
 * Opens the surface a SURFACE command names and hands it to the present thread. An empty name, or a
 * mapping that is missing or doesn't match the layout, stops drawing.
 *
 * @param r the frame's body after the type byte
 */
static void openSurface(FrameReader r) {
    uint32_t generation = 0;
    uint16_t nameLen = 0;
    if (!r.get(generation) || !r.get(nameLen)) {
        return;
    }
    char name[512];
    if (nameLen >= sizeof(name) || !r.bytes(reinterpret_cast<uint8_t *>(name), nameLen)) {
        return;
    }
    name[nameLen] = 0;
    SurfaceView *surface = new SurfaceView();
    if (nameLen == 0 || !g_presentHooked.load(std::memory_order_acquire)) {
        requestSurface(surface);
        return;
    }
    surface->mapping = OpenFileMappingA(FILE_MAP_READ | FILE_MAP_WRITE, FALSE, name);
    if (surface->mapping != nullptr) {
        surface->view = static_cast<uint8_t *>(MapViewOfFile(surface->mapping, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 0));
    }
    bool valid = false;
    if (surface->view != nullptr) {
        MEMORY_BASIC_INFORMATION info{};
        uint32_t header[4] = {};
        std::memcpy(header, surface->view, sizeof(header));
        uint64_t need = SURFACE_PIXELS + uint64_t(header[1]) * header[2] * 4 * header[3];
        valid = VirtualQuery(surface->view, &info, sizeof(info)) == sizeof(info)
                && header[0] == SURFACE_MAGIC
                && header[1] > 0 && header[1] <= SURFACE_MAX_SIDE
                && header[2] > 0 && header[2] <= SURFACE_MAX_SIDE
                && header[3] > 0 && header[3] <= SURFACE_MAX_SLOTS
                && info.RegionSize >= need;
        surface->generation = generation;
        surface->width = header[1];
        surface->height = header[2];
        surface->slots = header[3];
    }
    if (!valid) {
        freeSurface(surface);
        surface = new SurfaceView(); // a stop request: never draw a surface we can't trust
    }
    requestSurface(surface);
}

/**
 * Sets the frame cap a FRAME_LIMIT command names and wakes a pump waiting out a frame.
 *
 * @param r the frame's body after the type byte
 */
static void setFrameLimit(FrameReader r) {
    uint32_t fps = 0;
    if (!r.get(fps)) {
        return;
    }
    g_frameLimit.store(fps > MAX_FRAME_LIMIT ? 0 : fps, std::memory_order_relaxed);
    SetEvent(g_limitChanged);
}

/**
 * Reads exactly len bytes from the socket. Returns false if the connection closed early.
 */
static bool recvExact(char *buf, int len) {
    int got = 0;
    while (got < len) {
        int n = recv(g_sock, buf + got, len - got, 0);
        if (n <= 0) {
            return false;
        }
        got += n;
    }
    return true;
}

/**
 * Sends exactly len bytes to the socket. Returns false on any socket error.
 */
static bool sendExact(const char *buf, int len) {
    int sent = 0;
    while (sent < len) {
        int n = send(g_sock, buf + sent, len - sent, 0);
        if (n <= 0) {
            return false;
        }
        sent += n;
    }
    return true;
}

/**
 * The per-connection reader thread: reads command frames until the socket closes, then tells the
 * writer thread the connection is gone.
 *
 * @return 0
 */
static DWORD WINAPI readerThread(LPVOID) {
    std::vector<uint8_t> frame;
    for (;;) {
        uint32_t len = 0;
        if (!recvExact(reinterpret_cast<char *>(&len), 4) || len == 0 || len > MAX_FRAME) {
            break;
        }
        frame.resize(len);
        if (!recvExact(reinterpret_cast<char *>(frame.data()), static_cast<int>(len))) {
            break;
        }
        FrameReader r{frame.data() + 1, len - 1u};
        switch (frame[0]) {
            case CMD_CALLS:
                queueCalls(r);
                break;
            case CMD_SURFACE:
                openSurface(r);
                break;
            case CMD_FRAME_LIMIT:
                setFrameLimit(r);
                break;
            default:
                break; // an unknown command is skipped, so a newer client can still talk to this DLL
        }
    }
    g_connBroken.store(true, std::memory_order_release);
    if (g_dataEvent) {
        SetEvent(g_dataEvent);
    }
    return 0;
}

/**
 * Reads the handshake and installs any hooks not installed yet. The handshake is: magic "KRK4", a
 * u16 slot count, each slot's rva, id configuration, argument count and capture plan, then the
 * Client* slot, the pump function and the present function. A slot whose rva is already hooked is
 * left alone, so a re-handshake after a reconnect only adds new ones; the pump and present hooks
 * install once.
 *
 * @return true if the handshake parsed; false if the connection closed mid-handshake
 */
static bool readHandshake() {
    char magic[4];
    if (!recvExact(magic, 4) || std::memcmp(magic, "KRK4", 4) != 0) {
        return false;
    }
    uint16_t count = 0;
    if (!recvExact(reinterpret_cast<char *>(&count), 2)) {
        return false;
    }
    for (uint16_t i = 0; i < count; i++) {
        Slot slot;
        if (!recvExact(reinterpret_cast<char *>(&slot.rva), 8)
                || !recvExact(reinterpret_cast<char *>(&slot.fixedId), 4)
                || !recvExact(reinterpret_cast<char *>(&slot.idArg), 1)
                || !recvExact(reinterpret_cast<char *>(&slot.args), 1)
                || !recvExact(reinterpret_cast<char *>(&slot.planLen), 1)) {
            return false;
        }
        if (slot.planLen > MAX_PLAN) {
            return false;
        }
        for (uint8_t p = 0; p < slot.planLen; p++) {
            if (!recvExact(reinterpret_cast<char *>(&slot.plan[p]), 2)) {
                return false;
            }
        }
        installSlot(slot);
    }
    uint64_t tail[3] = {0, 0, 0};
    if (!recvExact(reinterpret_cast<char *>(tail), sizeof(tail))) {
        return false;
    }
    g_clientGlobal.store(tail[0], std::memory_order_relaxed);
    installPump(tail[1]);
    installPresent(tail[2]);
    sendStatus(count);
    return true;
}

/**
 * Hooks a function once with a dedicated detour.
 *
 * @param rva        the function's rva, 0 to skip
 * @param detour     the detour
 * @param trampoline receives the original
 * @param hooked     set once the hook is live; a set flag skips the install
 */
static void installOnce(uint64_t rva, void *detour, void **trampoline, std::atomic<bool> &hooked) {
    if (rva == 0 || rva >= g_imageSize || hooked.load(std::memory_order_acquire)) {
        return;
    }
    void *target = reinterpret_cast<void *>(g_base + rva);
    if (MH_CreateHook(target, detour, trampoline) != MH_OK) {
        return;
    }
    if (MH_EnableHook(target) != MH_OK) {
        MH_RemoveHook(target);
        return;
    }
    hooked.store(true, std::memory_order_release);
}

/**
 * Hooks the per-frame pump function, unless it is hooked already.
 *
 * @param rva the function's rva, 0 for none
 */
static void installPump(uint64_t rva) {
    installOnce(rva, reinterpret_cast<void *>(&pumpDetour), &g_pumpTrampoline, g_pumpHooked);
}

static void __fastcall presentDetour(void *self);

/**
 * Hooks the present function, unless it is hooked already.
 *
 * @param rva the function's rva, 0 for none
 */
static void installPresent(uint64_t rva) {
    installOnce(rva, reinterpret_cast<void *>(&presentDetour), &g_presentTrampoline, g_presentHooked);
}

/**
 * Installs one slot's hook unless its rva is already hooked or the table is full.
 *
 * @param slot the slot to install (its target and trampoline are filled in here)
 */
static void installSlot(Slot slot) {
    int n = g_slotCount.load(std::memory_order_acquire);
    for (int i = 0; i < n; i++) {
        if (g_slots[i].rva == slot.rva) {
            return; // already hooked; leave it in place for the life of the process
        }
    }
    if (n >= MAX_SLOTS) {
        return;
    }
    slot.target = reinterpret_cast<void *>(g_base + slot.rva);
    g_slots[n] = slot;
    if (MH_CreateHook(g_slots[n].target, g_detours[n], &g_slots[n].trampoline) != MH_OK
            || MH_EnableHook(g_slots[n].target) != MH_OK) {
        return; // leave the slot count unchanged; this target simply never pushes
    }
    g_slotCount.store(n + 1, std::memory_order_release);
}

/**
 * Sends a reserved message straight to the socket. Writer thread only, like every socket write.
 *
 * @param id   the reserved id
 * @param regs the four register values
 * @return false if the send failed
 */
static bool sendDirect(int32_t id, const uint64_t regs[4]) {
    uint8_t body[48];
    int off = writeHead(body, id, regs);
    body[off++] = 0;
    uint32_t len = static_cast<uint32_t>(off);
    return sendExact(reinterpret_cast<const char *>(&len), 4)
           && sendExact(reinterpret_cast<const char *>(body), off);
}

/**
 * Sends the status message: how many of the requested hooks installed, which features are live and
 * the protocol version.
 *
 * @param requested the number of slots the handshake asked for
 */
static void sendStatus(uint16_t requested) {
    bool pump = g_pumpHooked.load();
    bool present = g_presentHooked.load();
    uint64_t features = (pump ? FEATURE_PUMP : 0) | (present ? FEATURE_PRESENT : 0)
                        | (pump || present ? FEATURE_FRAME_RATE : 0);
    uint64_t regs[4] = {static_cast<uint64_t>(g_slotCount.load(std::memory_order_acquire)), requested, features,
                        PROTOCOL_VERSION};
    sendDirect(STATUS_EVENT_ID, regs);
}

/**
 * The FRAME values last sent on this connection.
 */
struct FrameState {
    uint32_t width = 0xffffffffu;
    uint32_t height = 0xffffffffu;
    uint32_t version = 0xffffffffu;
    uint32_t generation = 0xffffffffu;
};

/**
 * Sends FRAME if the present thread's framebuffer, GL version or drawn surface changed since the
 * last one sent. Nothing is sent before the first present has run.
 *
 * @param last the values last sent, updated when a FRAME goes out
 * @return false if the send failed
 */
static bool sendFrameIfChanged(FrameState &last) {
    FrameState now;
    now.width = g_fbWidth.load(std::memory_order_relaxed);
    now.height = g_fbHeight.load(std::memory_order_relaxed);
    now.version = g_glVersion.load(std::memory_order_relaxed);
    now.generation = g_drawnGeneration.load(std::memory_order_relaxed);
    if (now.version == 0 && now.width == 0 && now.height == 0) {
        return true;
    }
    if (now.width == last.width && now.height == last.height && now.version == last.version
            && now.generation == last.generation) {
        return true;
    }
    last = now;
    uint64_t regs[4] = {now.width, now.height, now.version, now.generation};
    return sendDirect(FRAME_EVENT_ID, regs);
}

/**
 * The frame count and time FPS last measured from on this connection.
 */
struct FpsState {
    bool started = false;
    uint64_t frames = 0;
    int64_t ticks = 0;
};

/**
 * Sends FPS once a second or more has passed since the last: the frames counted since and how long
 * that was. Counts presents while the present hook is live, else frame steps; sends nothing while
 * neither is hooked.
 *
 * @param last the count and time last measured from, updated when an FPS goes out
 * @return false if the send failed
 */
static bool sendFpsIfDue(FpsState &last) {
    bool present = g_presentHooked.load(std::memory_order_acquire);
    if (!present && !g_pumpHooked.load(std::memory_order_acquire)) {
        return true;
    }
    uint64_t frames = present ? g_presents.load(std::memory_order_relaxed)
                              : g_frameSteps.load(std::memory_order_relaxed);
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    if (!last.started) {
        last.started = true;
        last.frames = frames;
        last.ticks = now.QuadPart;
        return true;
    }
    int64_t elapsed = now.QuadPart - last.ticks;
    if (elapsed < g_qpcFrequency) {
        return true;
    }
    uint64_t regs[4] = {frames - last.frames, static_cast<uint64_t>(elapsed * 1000000 / g_qpcFrequency),
                        g_frameLimit.load(std::memory_order_relaxed), present ? 1u : 0u};
    last.frames = frames;
    last.ticks = now.QuadPart;
    return sendDirect(FPS_EVENT_ID, regs);
}

/**
 * Drains the ring to the socket, framing each message with its 4-byte length, and sends FRAME when
 * the present thread's values change and FPS once a second, until a send fails or the reader thread
 * sees the socket close.
 *
 * @return false when the connection should be re-established
 */
static bool drainRing() {
    FrameState last;
    FpsState fps;
    for (;;) {
        uint32_t head = g_head.load(std::memory_order_relaxed);
        uint32_t tail = g_tail.load(std::memory_order_acquire);
        while (head != tail) {
            Msg &msg = g_ring[head & RING_MASK];
            if (!sendExact(reinterpret_cast<const char *>(&msg.len), 4)
                    || !sendExact(reinterpret_cast<const char *>(msg.data), msg.len)) {
                return false;
            }
            head++;
            g_head.store(head, std::memory_order_release);
            tail = g_tail.load(std::memory_order_acquire);
        }
        if (!sendFrameIfChanged(last) || !sendFpsIfDue(fps)) {
            return false;
        }
        if (g_connBroken.load(std::memory_order_acquire)) {
            return false;
        }
        WaitForSingleObject(g_dataEvent, 1000);
    }
}

/**
 * Opens the loopback socket back to the Kraken client. Java may still be binding when we load, so
 * this retries briefly rather than failing on the first refused connection.
 *
 * @param attempts how many times to retry, 100 ms apart
 * @return true once connected
 */
static bool connectToJava(int attempts) {
    g_sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (g_sock == INVALID_SOCKET) {
        return false;
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(KRAKEN_PORT);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
    for (int i = 0; i < attempts; i++) {
        if (connect(g_sock, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) == 0) {
            return true;
        }
        Sleep(100);
    }
    closesocket(g_sock);
    g_sock = INVALID_SOCKET;
    return false;
}

/**
 * The writer thread: connect, read the handshake, start the connection's reader thread, drain the
 * ring to the socket, and reconnect for the life of the process if the socket drops. Hooks stay
 * installed across reconnects; a dropped socket costs the client latency, not events, because the
 * Java side keeps polling as a fallback. A dropped socket also drops the queued calls, stops the
 * overlay, since nothing would keep it current, and lifts the frame cap.
 */
static DWORD WINAPI writerThread(LPVOID) {
    bool first = true;
    for (;;) {
        if (!connectToJava(first ? 50 : 10)) {
            Sleep(1000);
            continue;
        }
        first = false;
        g_connBroken.store(false, std::memory_order_release);
        if (readHandshake()) {
            HANDLE reader = CreateThread(nullptr, 0, readerThread, nullptr, 0, nullptr);
            drainRing();
            shutdown(g_sock, SD_BOTH);
            if (reader != nullptr) {
                WaitForSingleObject(reader, INFINITE);
                CloseHandle(reader);
            }
        }
        if (g_sock != INVALID_SOCKET) {
            closesocket(g_sock);
            g_sock = INVALID_SOCKET;
        }
        clearPendingCalls();
        requestSurface(new SurfaceView());
        g_frameLimit.store(0, std::memory_order_relaxed);
        SetEvent(g_limitChanged);
        Sleep(1000); // the writer reconnects every second, then Java re-sends the handshake
    }
}

// OpenGL constants beyond GL 1.1's gl.h.
#ifndef GL_BGRA
#define GL_BGRA 0x80E1
#endif
#ifndef GL_CLAMP_TO_EDGE
#define GL_CLAMP_TO_EDGE 0x812F
#endif
#define KGL_RGBA8 0x8058
#define KGL_TEXTURE0 0x84C0
#define KGL_ACTIVE_TEXTURE 0x84E0
#define KGL_TEXTURE_BINDING_2D 0x8069
#define KGL_TEXTURE_BASE_LEVEL 0x813C
#define KGL_TEXTURE_MAX_LEVEL 0x813D
#define KGL_SAMPLER_BINDING 0x8919
#define KGL_CURRENT_PROGRAM 0x8B8D
#define KGL_VERTEX_ARRAY_BINDING 0x85B5
#define KGL_DRAW_FRAMEBUFFER 0x8CA9
#define KGL_READ_FRAMEBUFFER 0x8CA8
#define KGL_DRAW_FRAMEBUFFER_BINDING 0x8CA6
#define KGL_READ_FRAMEBUFFER_BINDING 0x8CAA
#define KGL_FRAMEBUFFER_SRGB 0x8DB9
#define KGL_RASTERIZER_DISCARD 0x8C89
#define KGL_BLEND_DST_RGB 0x80C8
#define KGL_BLEND_SRC_RGB 0x80C9
#define KGL_BLEND_DST_ALPHA 0x80CA
#define KGL_BLEND_SRC_ALPHA 0x80CB
#define KGL_BLEND_EQUATION_RGB 0x8009
#define KGL_BLEND_EQUATION_ALPHA 0x883D
#define KGL_FUNC_ADD 0x8006
#define KGL_PIXEL_UNPACK_BUFFER 0x88EC
#define KGL_PIXEL_UNPACK_BUFFER_BINDING 0x88EF
#define KGL_CONTEXT_PROFILE_MASK 0x9126
#define KGL_CONTEXT_CORE_PROFILE_BIT 0x1
#define KGL_VERTEX_SHADER 0x8B31
#define KGL_FRAGMENT_SHADER 0x8B30
#define KGL_COMPILE_STATUS 0x8B81
#define KGL_LINK_STATUS 0x8B82
#define KGL_SAMPLE_ALPHA_TO_COVERAGE 0x809E

typedef char KGLchar;
typedef GLuint (APIENTRY *PFN_CreateShader)(GLenum);
typedef void (APIENTRY *PFN_ShaderSource)(GLuint, GLsizei, const KGLchar *const *, const GLint *);
typedef void (APIENTRY *PFN_CompileShader)(GLuint);
typedef void (APIENTRY *PFN_GetShaderiv)(GLuint, GLenum, GLint *);
typedef void (APIENTRY *PFN_DeleteShader)(GLuint);
typedef GLuint (APIENTRY *PFN_CreateProgram)();
typedef void (APIENTRY *PFN_AttachShader)(GLuint, GLuint);
typedef void (APIENTRY *PFN_BindFragDataLocation)(GLuint, GLuint, const KGLchar *);
typedef void (APIENTRY *PFN_LinkProgram)(GLuint);
typedef void (APIENTRY *PFN_GetProgramiv)(GLuint, GLenum, GLint *);
typedef void (APIENTRY *PFN_DeleteProgram)(GLuint);
typedef void (APIENTRY *PFN_UseProgram)(GLuint);
typedef GLint (APIENTRY *PFN_GetUniformLocation)(GLuint, const KGLchar *);
typedef void (APIENTRY *PFN_Uniform1i)(GLint, GLint);
typedef void (APIENTRY *PFN_GenVertexArrays)(GLsizei, GLuint *);
typedef void (APIENTRY *PFN_BindVertexArray)(GLuint);
typedef void (APIENTRY *PFN_BindBuffer)(GLenum, GLuint);
typedef void (APIENTRY *PFN_ActiveTexture)(GLenum);
typedef void (APIENTRY *PFN_BindFramebuffer)(GLenum, GLuint);
typedef void (APIENTRY *PFN_BindSampler)(GLuint, GLuint);
typedef void (APIENTRY *PFN_BlendFuncSeparate)(GLenum, GLenum, GLenum, GLenum);
typedef void (APIENTRY *PFN_BlendEquationSeparate)(GLenum, GLenum);

/**
 * The GL entry points beyond GL 1.1, loaded per context through wglGetProcAddress.
 */
struct GlFunctions {
    PFN_CreateShader createShader;
    PFN_ShaderSource shaderSource;
    PFN_CompileShader compileShader;
    PFN_GetShaderiv getShaderiv;
    PFN_DeleteShader deleteShader;
    PFN_CreateProgram createProgram;
    PFN_AttachShader attachShader;
    PFN_BindFragDataLocation bindFragDataLocation;
    PFN_LinkProgram linkProgram;
    PFN_GetProgramiv getProgramiv;
    PFN_DeleteProgram deleteProgram;
    PFN_UseProgram useProgram;
    PFN_GetUniformLocation getUniformLocation;
    PFN_Uniform1i uniform1i;
    PFN_GenVertexArrays genVertexArrays;
    PFN_BindVertexArray bindVertexArray;
    PFN_BindBuffer bindBuffer;
    PFN_ActiveTexture activeTexture;
    PFN_BindFramebuffer bindFramebuffer;
    PFN_BindSampler bindSampler;         // GL 3.3; null below
    PFN_BlendFuncSeparate blendFuncSeparate;
    PFN_BlendEquationSeparate blendEquationSeparate;
};

/**
 * The overlay's GL objects and upload state for one context. Present thread only.
 */
struct GlOverlay {
    HGLRC context = nullptr;
    int major = 0;
    int minor = 0;
    bool core = false;
    bool loaded = false;     // the entry points beyond GL 1.1 were looked up (broken if one is missing)
    bool built = false;      // the program and the VAO were built (broken if that failed)
    bool broken = false;     // this context can't draw the overlay (too old, a missing entry point, a shader error)
    GlFunctions fn{};
    GLuint program = 0;
    GLuint vao = 0;
    GLuint texture = 0;
    uint32_t texWidth = 0;
    uint32_t texHeight = 0;
    bool texValid = false;   // the texture has had a full upload since it was allocated
    uint32_t texTop = 0;     // the rows [texTop, texBottom) of the texture that may hold pixels
    uint32_t texBottom = 0;
    bool linear = false;     // the texture's filter: linear when it is stretched to the viewport
    uint32_t lastPublished = PUBLISHED_UNSEEN;
};

static GlOverlay g_gl;
static SurfaceView *g_surface = nullptr; // the surface being drawn; present thread only
static bool g_overlayFaulted = false;    // a fault while drawing turns the overlay off for the session
static HDC g_lastDc = nullptr;
static HWND g_lastWnd = nullptr;

/**
 * Looks up one GL entry point, treating the values some drivers return for a missing one as null.
 *
 * @param name the function's name
 * @return its address, or nullptr
 */
static void *glProc(const char *name) {
    void *p = reinterpret_cast<void *>(wglGetProcAddress(name));
    intptr_t v = reinterpret_cast<intptr_t>(p);
    if (v == 0 || v == 1 || v == 2 || v == 3 || v == -1) {
        return nullptr;
    }
    return p;
}

/**
 * Reads the current context's GL version and profile into g_gl.
 */
static void readGlVersion() {
    g_gl.major = 0;
    g_gl.minor = 0;
    const char *version = reinterpret_cast<const char *>(glGetString(GL_VERSION));
    if (version != nullptr) {
        int i = 0;
        while (version[i] >= '0' && version[i] <= '9') {
            g_gl.major = g_gl.major * 10 + (version[i++] - '0');
        }
        if (version[i] == '.') {
            i++;
            while (version[i] >= '0' && version[i] <= '9') {
                g_gl.minor = g_gl.minor * 10 + (version[i++] - '0');
            }
        }
    }
    g_gl.core = false;
    if (g_gl.major > 3 || (g_gl.major == 3 && g_gl.minor >= 2)) {
        GLint mask = 0;
        glGetIntegerv(KGL_CONTEXT_PROFILE_MASK, &mask);
        g_gl.core = (mask & KGL_CONTEXT_CORE_PROFILE_BIT) != 0;
    }
}

/**
 * Whether the context is at least a given GL version.
 *
 * @param major the major version
 * @param minor the minor version
 * @return true if the context's version is at least major.minor
 */
static bool glAtLeast(int major, int minor) {
    return g_gl.major > major || (g_gl.major == major && g_gl.minor >= minor);
}

/**
 * Compiles one shader from a version line and a body.
 *
 * @param type   the shader type
 * @param header the {@code #version} line
 * @param body   the rest of the GLSL source
 * @return the shader, or 0 if it didn't compile
 */
static GLuint compileShader(GLenum type, const char *header, const char *body) {
    GLuint shader = g_gl.fn.createShader(type);
    if (shader == 0) {
        return 0;
    }
    const char *sources[2] = {header, body};
    g_gl.fn.shaderSource(shader, 2, sources, nullptr);
    g_gl.fn.compileShader(shader);
    GLint ok = 0;
    g_gl.fn.getShaderiv(shader, KGL_COMPILE_STATUS, &ok);
    if (!ok) {
        g_gl.fn.deleteShader(shader);
        return 0;
    }
    return shader;
}

/**
 * Looks up the entry points beyond GL 1.1 for the current context. Leaves g_gl.broken set when the
 * context is older than GL 3.0 or lacks one. Changes no GL state.
 */
static void loadGlFunctions() {
    g_gl.loaded = true;
    if (!glAtLeast(3, 0)) {
        g_gl.broken = true;
        return;
    }
    GlFunctions &f = g_gl.fn;
    f.createShader = reinterpret_cast<PFN_CreateShader>(glProc("glCreateShader"));
    f.shaderSource = reinterpret_cast<PFN_ShaderSource>(glProc("glShaderSource"));
    f.compileShader = reinterpret_cast<PFN_CompileShader>(glProc("glCompileShader"));
    f.getShaderiv = reinterpret_cast<PFN_GetShaderiv>(glProc("glGetShaderiv"));
    f.deleteShader = reinterpret_cast<PFN_DeleteShader>(glProc("glDeleteShader"));
    f.createProgram = reinterpret_cast<PFN_CreateProgram>(glProc("glCreateProgram"));
    f.attachShader = reinterpret_cast<PFN_AttachShader>(glProc("glAttachShader"));
    f.bindFragDataLocation = reinterpret_cast<PFN_BindFragDataLocation>(glProc("glBindFragDataLocation"));
    f.linkProgram = reinterpret_cast<PFN_LinkProgram>(glProc("glLinkProgram"));
    f.getProgramiv = reinterpret_cast<PFN_GetProgramiv>(glProc("glGetProgramiv"));
    f.deleteProgram = reinterpret_cast<PFN_DeleteProgram>(glProc("glDeleteProgram"));
    f.useProgram = reinterpret_cast<PFN_UseProgram>(glProc("glUseProgram"));
    f.getUniformLocation = reinterpret_cast<PFN_GetUniformLocation>(glProc("glGetUniformLocation"));
    f.uniform1i = reinterpret_cast<PFN_Uniform1i>(glProc("glUniform1i"));
    f.genVertexArrays = reinterpret_cast<PFN_GenVertexArrays>(glProc("glGenVertexArrays"));
    f.bindVertexArray = reinterpret_cast<PFN_BindVertexArray>(glProc("glBindVertexArray"));
    f.bindBuffer = reinterpret_cast<PFN_BindBuffer>(glProc("glBindBuffer"));
    f.activeTexture = reinterpret_cast<PFN_ActiveTexture>(glProc("glActiveTexture"));
    f.bindFramebuffer = reinterpret_cast<PFN_BindFramebuffer>(glProc("glBindFramebuffer"));
    f.bindSampler = glAtLeast(3, 3) ? reinterpret_cast<PFN_BindSampler>(glProc("glBindSampler")) : nullptr;
    f.blendFuncSeparate = reinterpret_cast<PFN_BlendFuncSeparate>(glProc("glBlendFuncSeparate"));
    f.blendEquationSeparate = reinterpret_cast<PFN_BlendEquationSeparate>(glProc("glBlendEquationSeparate"));
    if (!f.createShader || !f.shaderSource || !f.compileShader || !f.getShaderiv || !f.deleteShader
            || !f.createProgram || !f.attachShader || !f.bindFragDataLocation || !f.linkProgram || !f.getProgramiv
            || !f.deleteProgram || !f.useProgram || !f.getUniformLocation || !f.uniform1i || !f.genVertexArrays
            || !f.bindVertexArray || !f.bindBuffer || !f.activeTexture || !f.bindFramebuffer
            || !f.blendFuncSeparate || !f.blendEquationSeparate || (glAtLeast(3, 3) && !f.bindSampler)) {
        g_gl.broken = true;
    }
}

/**
 * Builds the program and the empty VAO for the current context. Leaves g_gl.broken set when a shader
 * doesn't compile or the program doesn't link. Runs with the client's GL state saved, since it binds
 * the new program to set its sampler.
 */
static void buildGl() {
    g_gl.built = true;
    GlFunctions &f = g_gl.fn;
    // A full-screen triangle from gl_VertexID; image rows are top-down, so the sample flips v.
    const char *header = glAtLeast(3, 3) ? "#version 330 core\n" : glAtLeast(3, 2) ? "#version 150\n" : "#version 130\n";
    const char *vertexBody =
            "out vec2 uv;\n"
            "void main() {\n"
            "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
            "    uv = p;\n"
            "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
            "}\n";
    const char *fragmentBody =
            "in vec2 uv;\n"
            "uniform sampler2D overlay;\n"
            "out vec4 colour;\n"
            "void main() {\n"
            "    colour = texture(overlay, vec2(uv.x, 1.0 - uv.y));\n"
            "}\n";
    GLuint vs = compileShader(KGL_VERTEX_SHADER, header, vertexBody);
    GLuint fs = compileShader(KGL_FRAGMENT_SHADER, header, fragmentBody);
    if (vs == 0 || fs == 0) {
        if (vs) f.deleteShader(vs);
        if (fs) f.deleteShader(fs);
        g_gl.broken = true;
        return;
    }
    GLuint program = f.createProgram();
    f.attachShader(program, vs);
    f.attachShader(program, fs);
    f.bindFragDataLocation(program, 0, "colour");
    f.linkProgram(program);
    f.deleteShader(vs);
    f.deleteShader(fs);
    GLint linked = 0;
    f.getProgramiv(program, KGL_LINK_STATUS, &linked);
    if (!linked) {
        f.deleteProgram(program);
        g_gl.broken = true;
        return;
    }
    f.useProgram(program);
    f.uniform1i(f.getUniformLocation(program, "overlay"), 0);
    g_gl.program = program;
    f.genVertexArrays(1, &g_gl.vao);
}

/**
 * The client's GL state the overlay changes, saved before and restored after.
 */
struct GlSaved {
    GLint program;
    GLint vao;
    GLint activeTexture;
    GLint texture0;
    GLint sampler0;
    GLint drawFramebuffer;
    GLint readFramebuffer;
    GLint drawBuffer;
    GLint viewport[4];
    GLboolean scissor;
    GLboolean depth;
    GLboolean stencil;
    GLboolean cull;
    GLboolean blend;
    GLboolean srgb;
    GLboolean discard;
    GLboolean alphaToCoverage;
    GLboolean logicOp;
    GLint blendSrcRgb;
    GLint blendDstRgb;
    GLint blendSrcAlpha;
    GLint blendDstAlpha;
    GLint blendEqRgb;
    GLint blendEqAlpha;
    GLboolean colourMask[4];
    GLint polygonMode[2];
    GLint unpackAlignment;
    GLint unpackRowLength;
    GLint unpackSkipRows;
    GLint unpackSkipPixels;
    GLint unpackBuffer;
};

/**
 * Saves the client's GL state the overlay changes, and binds texture unit 0.
 *
 * @param s where to save it
 */
static void saveGl(GlSaved &s) {
    GlFunctions &f = g_gl.fn;
    glGetIntegerv(KGL_CURRENT_PROGRAM, &s.program);
    glGetIntegerv(KGL_VERTEX_ARRAY_BINDING, &s.vao);
    glGetIntegerv(KGL_ACTIVE_TEXTURE, &s.activeTexture);
    f.activeTexture(KGL_TEXTURE0);
    glGetIntegerv(KGL_TEXTURE_BINDING_2D, &s.texture0);
    s.sampler0 = 0;
    if (f.bindSampler) {
        glGetIntegerv(KGL_SAMPLER_BINDING, &s.sampler0);
    }
    glGetIntegerv(KGL_DRAW_FRAMEBUFFER_BINDING, &s.drawFramebuffer);
    glGetIntegerv(KGL_READ_FRAMEBUFFER_BINDING, &s.readFramebuffer);
    glGetIntegerv(GL_VIEWPORT, s.viewport);
    s.scissor = glIsEnabled(GL_SCISSOR_TEST);
    s.depth = glIsEnabled(GL_DEPTH_TEST);
    s.stencil = glIsEnabled(GL_STENCIL_TEST);
    s.cull = glIsEnabled(GL_CULL_FACE);
    s.blend = glIsEnabled(GL_BLEND);
    s.srgb = glIsEnabled(KGL_FRAMEBUFFER_SRGB);
    s.discard = glIsEnabled(KGL_RASTERIZER_DISCARD);
    s.alphaToCoverage = glIsEnabled(KGL_SAMPLE_ALPHA_TO_COVERAGE);
    s.logicOp = glIsEnabled(GL_COLOR_LOGIC_OP);
    glGetIntegerv(KGL_BLEND_SRC_RGB, &s.blendSrcRgb);
    glGetIntegerv(KGL_BLEND_DST_RGB, &s.blendDstRgb);
    glGetIntegerv(KGL_BLEND_SRC_ALPHA, &s.blendSrcAlpha);
    glGetIntegerv(KGL_BLEND_DST_ALPHA, &s.blendDstAlpha);
    glGetIntegerv(KGL_BLEND_EQUATION_RGB, &s.blendEqRgb);
    glGetIntegerv(KGL_BLEND_EQUATION_ALPHA, &s.blendEqAlpha);
    glGetBooleanv(GL_COLOR_WRITEMASK, s.colourMask);
    s.polygonMode[0] = GL_FILL;
    s.polygonMode[1] = GL_FILL;
    glGetIntegerv(GL_POLYGON_MODE, s.polygonMode);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &s.unpackAlignment);
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &s.unpackRowLength);
    glGetIntegerv(GL_UNPACK_SKIP_ROWS, &s.unpackSkipRows);
    glGetIntegerv(GL_UNPACK_SKIP_PIXELS, &s.unpackSkipPixels);
    glGetIntegerv(KGL_PIXEL_UNPACK_BUFFER_BINDING, &s.unpackBuffer);
    // The draw buffer is per framebuffer: read framebuffer 0's after binding it in drawOverlay
    s.drawBuffer = -1;
}

/**
 * Sets one capability on or off.
 *
 * @param cap the capability
 * @param on  whether it should be enabled
 */
static void setCap(GLenum cap, GLboolean on) {
    if (on) {
        glEnable(cap);
    } else {
        glDisable(cap);
    }
}

/**
 * Restores the client's GL state saved by saveGl.
 *
 * @param s the saved state
 */
static void restoreGl(const GlSaved &s) {
    GlFunctions &f = g_gl.fn;
    if (s.drawBuffer >= 0) {
        glDrawBuffer(static_cast<GLenum>(s.drawBuffer));
    }
    f.bindFramebuffer(KGL_DRAW_FRAMEBUFFER, static_cast<GLuint>(s.drawFramebuffer));
    f.bindFramebuffer(KGL_READ_FRAMEBUFFER, static_cast<GLuint>(s.readFramebuffer));
    glViewport(s.viewport[0], s.viewport[1], s.viewport[2], s.viewport[3]);
    setCap(GL_SCISSOR_TEST, s.scissor);
    setCap(GL_DEPTH_TEST, s.depth);
    setCap(GL_STENCIL_TEST, s.stencil);
    setCap(GL_CULL_FACE, s.cull);
    setCap(GL_BLEND, s.blend);
    setCap(KGL_FRAMEBUFFER_SRGB, s.srgb);
    setCap(KGL_RASTERIZER_DISCARD, s.discard);
    setCap(KGL_SAMPLE_ALPHA_TO_COVERAGE, s.alphaToCoverage);
    setCap(GL_COLOR_LOGIC_OP, s.logicOp);
    f.blendFuncSeparate(s.blendSrcRgb, s.blendDstRgb, s.blendSrcAlpha, s.blendDstAlpha);
    f.blendEquationSeparate(s.blendEqRgb, s.blendEqAlpha);
    glColorMask(s.colourMask[0], s.colourMask[1], s.colourMask[2], s.colourMask[3]);
    if (s.polygonMode[0] != GL_FILL) {
        glPolygonMode(GL_FRONT_AND_BACK, s.polygonMode[0]);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, s.unpackAlignment);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, s.unpackRowLength);
    glPixelStorei(GL_UNPACK_SKIP_ROWS, s.unpackSkipRows);
    glPixelStorei(GL_UNPACK_SKIP_PIXELS, s.unpackSkipPixels);
    f.bindBuffer(KGL_PIXEL_UNPACK_BUFFER, static_cast<GLuint>(s.unpackBuffer));
    if (f.bindSampler) {
        f.bindSampler(0, static_cast<GLuint>(s.sampler0));
    }
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(s.texture0));
    f.activeTexture(static_cast<GLenum>(s.activeTexture));
    f.bindVertexArray(static_cast<GLuint>(s.vao));
    f.useProgram(static_cast<GLuint>(s.program));
}

/**
 * Uploads rows [top, bottom) of a slot into the bound texture.
 *
 * @param pixels the slot's pixels
 * @param width  the surface width
 * @param top    the first row
 * @param bottom one past the last row
 */
static void uploadRows(const uint8_t *pixels, uint32_t width, uint32_t top, uint32_t bottom) {
    if (bottom <= top) {
        return;
    }
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, static_cast<GLint>(top), static_cast<GLsizei>(width),
                    static_cast<GLsizei>(bottom - top), GL_BGRA, GL_UNSIGNED_BYTE,
                    pixels + static_cast<size_t>(top) * width * 4);
}

/**
 * Takes the newest published frame of the surface into the bound texture, if there is one the
 * texture doesn't have yet. Follows the surface's triple-buffer handshake: claim the slot in
 * {@code reading}, fence, confirm {@code published} didn't move, upload the rows that changed
 * against the texture, release the slot.
 *
 * @param surface the open surface
 */
static void takeFrame(const SurfaceView &surface) {
    volatile uint32_t *published = reinterpret_cast<volatile uint32_t *>(surface.view + SURFACE_PUBLISHED);
    volatile int32_t *reading = reinterpret_cast<volatile int32_t *>(surface.view + SURFACE_READING);
    uint32_t pub = *published;
    if (pub == PUBLISHED_NONE || pub == g_gl.lastPublished) {
        return;
    }
    uint32_t slot = pub & 0xf;
    if (slot >= surface.slots) {
        return;
    }
    *reading = static_cast<int32_t>(slot);
    MemoryBarrier();
    if (*published != pub) {
        *reading = -1;
        return; // Java published again in between; take the newer frame next present
    }
    const uint32_t *header = reinterpret_cast<const uint32_t *>(
            surface.view + SURFACE_SLOT_HEADERS + slot * SURFACE_SLOT_HEADER_SIZE);
    uint32_t top = header[0];
    uint32_t bottom = header[1];
    if (top > bottom || bottom > surface.height) {
        top = 0;
        bottom = surface.height;
    }
    const uint8_t *pixels = surface.view + SURFACE_PIXELS + static_cast<size_t>(slot) * surface.width * surface.height * 4;
    if (!g_gl.texValid) {
        uploadRows(pixels, surface.width, 0, surface.height);
    } else {
        // Rows outside a slot's band are clear, so only the texture's old band and the new one can differ
        uint32_t t0 = g_gl.texTop, t1 = g_gl.texBottom;
        bool texEmpty = t1 <= t0, slotEmpty = bottom <= top;
        if (texEmpty) {
            uploadRows(pixels, surface.width, top, bottom);
        } else if (slotEmpty) {
            uploadRows(pixels, surface.width, t0, t1);
        } else if (t0 <= bottom && top <= t1) {
            uploadRows(pixels, surface.width, t0 < top ? t0 : top, t1 > bottom ? t1 : bottom);
        } else {
            uploadRows(pixels, surface.width, t0, t1);
            uploadRows(pixels, surface.width, top, bottom);
        }
    }
    MemoryBarrier();
    *reading = -1;
    g_gl.texValid = true;
    g_gl.texTop = top;
    g_gl.texBottom = bottom;
    g_gl.lastPublished = pub;
}

/**
 * Uploads the newest frame and draws the overlay over framebuffer 0. Saves the client's GL state
 * first and restores it after, even when drawing faults.
 *
 * @param surface the open surface
 * @param width   the window's client width
 * @param height  the window's client height
 * @return false if drawing faulted
 */
static bool drawOverlay(const SurfaceView &surface, uint32_t width, uint32_t height);

/**
 * The body of drawOverlay, between saving and restoring the client's state.
 *
 * @param surface the open surface
 * @param width   the window's client width
 * @param height  the window's client height
 * @param s       the saved state, whose draw buffer this fills in once framebuffer 0 is bound
 */
static void drawOverlayBody(const SurfaceView &surface, uint32_t width, uint32_t height, GlSaved &s) {
    GlFunctions &f = g_gl.fn;
    if (!g_gl.built) {
        buildGl();
        if (g_gl.broken) {
            return;
        }
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
    glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
    f.bindBuffer(KGL_PIXEL_UNPACK_BUFFER, 0);
    if (f.bindSampler) {
        f.bindSampler(0, 0);
    }
    if (g_gl.texture == 0) {
        glGenTextures(1, &g_gl.texture);
        g_gl.texWidth = 0;
    }
    glBindTexture(GL_TEXTURE_2D, g_gl.texture);
    bool linear = surface.width != width || surface.height != height;
    if (g_gl.texWidth != surface.width || g_gl.texHeight != surface.height) {
        glTexImage2D(GL_TEXTURE_2D, 0, KGL_RGBA8, static_cast<GLsizei>(surface.width),
                     static_cast<GLsizei>(surface.height), 0, GL_BGRA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, KGL_TEXTURE_BASE_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, KGL_TEXTURE_MAX_LEVEL, 0);
        g_gl.texWidth = surface.width;
        g_gl.texHeight = surface.height;
        g_gl.texValid = false;
        g_gl.lastPublished = PUBLISHED_UNSEEN;
        g_gl.linear = !linear; // forces the filter below
    }
    if (g_gl.linear != linear) {
        GLint filter = linear ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        g_gl.linear = linear;
    }
    takeFrame(surface);
    if (!g_gl.texValid || g_gl.texBottom <= g_gl.texTop) {
        return; // nothing to show: an empty overlay costs no draw
    }

    f.bindFramebuffer(KGL_DRAW_FRAMEBUFFER, 0);
    GLint drawBuffer = GL_BACK;
    glGetIntegerv(GL_DRAW_BUFFER, &drawBuffer);
    if (drawBuffer != GL_BACK) {
        s.drawBuffer = drawBuffer;
        glDrawBuffer(GL_BACK);
    }
    glViewport(0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height));
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(KGL_FRAMEBUFFER_SRGB);
    glDisable(KGL_RASTERIZER_DISCARD);
    glDisable(KGL_SAMPLE_ALPHA_TO_COVERAGE);
    glDisable(GL_COLOR_LOGIC_OP);
    glEnable(GL_BLEND);
    f.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    f.blendEquationSeparate(KGL_FUNC_ADD, KGL_FUNC_ADD);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    if (s.polygonMode[0] != GL_FILL) {
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
    }
    f.useProgram(g_gl.program);
    f.bindVertexArray(g_gl.vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

/**
 * Saves the client's GL state under an SEH guard.
 *
 * @param s where to save it
 * @return false if it faulted
 */
static bool guardedSave(GlSaved &s) {
    bool faulted = false;
    GUARD_BEGIN saveGl(s); GUARD_END
    return !faulted;
}

/**
 * Runs the draw under an SEH guard.
 *
 * @param surface the open surface
 * @param width   the window's client width
 * @param height  the window's client height
 * @param s       the saved state
 * @return false if it faulted
 */
static bool guardedDraw(const SurfaceView &surface, uint32_t width, uint32_t height, GlSaved &s) {
    bool faulted = false;
    GUARD_BEGIN drawOverlayBody(surface, width, height, s); GUARD_END
    return !faulted;
}

/**
 * Restores the client's GL state under an SEH guard.
 *
 * @param s the saved state
 * @return false if it faulted
 */
static bool guardedRestore(const GlSaved &s) {
    bool faulted = false;
    GUARD_BEGIN restoreGl(s); GUARD_END
    return !faulted;
}

static bool drawOverlay(const SurfaceView &surface, uint32_t width, uint32_t height) {
    if (!g_gl.loaded) {
        bool faulted = false;
        GUARD_BEGIN loadGlFunctions(); GUARD_END
        if (faulted) {
            return false;
        }
    }
    if (g_gl.broken) {
        return true;
    }
    GlSaved s{};
    if (!guardedSave(s)) {
        return false;
    }
    bool drew = guardedDraw(surface, width, height, s);
    bool restored = guardedRestore(s);
    return drew && restored;
}

/**
 * Takes a pending surface (or stop request) from the reader thread, dropping the one being drawn.
 */
static void adoptPendingSurface() {
    SurfaceView *next = g_pendingSurface.exchange(nullptr, std::memory_order_acq_rel);
    if (next == nullptr) {
        return;
    }
    freeSurface(g_surface);
    g_surface = nullptr;
    if (next->view == nullptr) {
        freeSurface(next);
        return;
    }
    g_surface = next;
    *reinterpret_cast<volatile int32_t *>(g_surface->view + SURFACE_READING) = -1;
    g_gl.texValid = false;
    g_gl.lastPublished = PUBLISHED_UNSEEN;
}

/**
 * Publishes a value for FRAME, waking the writer thread when it changed.
 *
 * @param slot  the atomic
 * @param value the new value
 * @return true if it changed
 */
static bool publishFrameValue(std::atomic<uint32_t> &slot, uint32_t value) {
    return slot.exchange(value, std::memory_order_relaxed) != value;
}

/**
 * The overlay's share of one present: track the framebuffer and context, take a new surface, and
 * draw the newest frame. Present thread only; never touches the ring.
 */
static void overlayFrame() {
    adoptPendingSurface();
    HDC dc = wglGetCurrentDC();
    HGLRC rc = wglGetCurrentContext();
    if (dc == nullptr || rc == nullptr) {
        return;
    }
    if (dc != g_lastDc) {
        g_lastDc = dc;
        g_lastWnd = WindowFromDC(dc);
    }
    RECT rect{};
    uint32_t width = 0;
    uint32_t height = 0;
    if (g_lastWnd != nullptr && GetClientRect(g_lastWnd, &rect)) {
        width = static_cast<uint32_t>(rect.right - rect.left);
        height = static_cast<uint32_t>(rect.bottom - rect.top);
    }
    if (rc != g_gl.context) {
        // A new context: its objects are not ours to reuse, so start over
        g_gl = GlOverlay();
        g_gl.context = rc;
        readGlVersion();
    }
    bool changed = publishFrameValue(g_fbWidth, width);
    changed |= publishFrameValue(g_fbHeight, height);
    changed |= publishFrameValue(g_glVersion, (static_cast<uint32_t>(g_gl.major) << 16) | static_cast<uint32_t>(g_gl.minor));

    uint32_t generation = 0;
    if (g_surface != nullptr && !g_overlayFaulted && !g_gl.broken && glAtLeast(3, 0) && width > 0 && height > 0) {
        if (drawOverlay(*g_surface, width, height)) {
            generation = g_gl.broken ? 0 : g_surface->generation;
        } else {
            g_overlayFaulted = true;
        }
    }
    changed |= publishFrameValue(g_drawnGeneration, generation);
    if (changed && g_dataEvent) {
        SetEvent(g_dataEvent);
    }
}

/**
 * The present detour: draws the overlay over the finished frame, then presents it.
 *
 * @param self the client's GL context object
 */
static void __fastcall presentDetour(void *self) {
    g_presents.fetch_add(1, std::memory_order_relaxed);
    overlayFrame();
    using Fn = void(__fastcall *)(void *);
    reinterpret_cast<Fn>(g_presentTrampoline)(self);
}

/**
 * Worker started off the loader lock: resolve the module base and size, set up MinHook and the
 * detour table, then run the writer thread. Returns non-zero on any setup failure.
 */
static DWORD WINAPI init(LPVOID) {
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        return 1;
    }
    g_base = reinterpret_cast<uintptr_t>(GetModuleHandleW(L"osclient.exe"));
    if (g_base == 0) {
        return 1;
    }
    const IMAGE_DOS_HEADER *dos = reinterpret_cast<const IMAGE_DOS_HEADER *>(g_base);
    const IMAGE_NT_HEADERS64 *nt = reinterpret_cast<const IMAGE_NT_HEADERS64 *>(g_base + dos->e_lfanew);
    g_imageSize = nt->OptionalHeader.SizeOfImage;
    fillDetours(std::make_index_sequence<MAX_SLOTS>{});
    LARGE_INTEGER frequency;
    QueryPerformanceFrequency(&frequency);
    g_qpcFrequency = frequency.QuadPart;
    g_dataEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    g_limitChanged = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (g_dataEvent == nullptr || g_limitChanged == nullptr || MH_Initialize() != MH_OK) {
        return 1;
    }
    writerThread(nullptr);
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hinst);
        // A named mutex refuses a second injection into the same process.
        HANDLE mutex = CreateMutexW(nullptr, TRUE, L"Local\\KrakenHookInstance");
        if (mutex != nullptr && GetLastError() == ERROR_ALREADY_EXISTS) {
            return TRUE;
        }
        // Do the real work on our own thread; DllMain must not block or call most APIs under the loader lock.
        CreateThread(nullptr, 0, init, nullptr, 0, nullptr);
    }
    return TRUE;
}
