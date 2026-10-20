// kraken_hook.dll - injected into osclient.exe to turn the client's own event raises into push
// events for the Kraken client.
//
// On load it connects back to the Kraken client over a loopback socket and receives a handshake: a
// table of raise functions to hook, each with the event id it carries (fixed, or read from an
// argument register) and a capture plan saying which arguments to forward and how to read them. It
// inline-hooks each one with a generic detour. When the client calls a hooked raiser the detour
// captures the id and the planned values, pushes a message into a lock-free ring, and returns
// through the original; a writer thread drains the ring to the socket. The Kraken client turns each
// message back into the matching com.kraken.api.events event.
//
// Nothing here is hard-coded per client build: every RVA, event id and capture plan comes from the
// mapping on the Java side and arrives in the handshake. See README.md and docs/event-hooks-design.md.
//
// Why a socket and not a JNI/JNA callback: this DLL runs inside osclient.exe, which has no JVM, so a
// Java callback pointer means nothing in this address space. The socket is the bridge.

// winsock2.h must come before windows.h, and WIN32_LEAN_AND_MEAN stops windows.h from pulling in the
// old Winsock 1 winsock.h, which would clash with winsock2.h.
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <cstdint>
#include <cstring>
#include <atomic>
#include <utility>
#include "MinHook.h"

#pragma comment(lib, "ws2_32.lib")

// Must match NativeEventServer.PORT on the Java side.
static const unsigned short KRAKEN_PORT = 42069;

// Protocol v2 limits and op codes. Must match NativeProtocol on the Java side.
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

// Status message: a reserved event id the writer thread sends once after a handshake so Java can log
// how many hooks installed. regs[0] = installed, regs[1] = requested.
static const int32_t STATUS_EVENT_ID = -2;

// SEH guards the pointer dereferences a capture plan does: a bad argument pointer must raise rather
// than crash the client. MSVC (the build's preferred toolchain) supports it directly; under MinGW
// the dereferences run unguarded.
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

static SOCKET g_sock = INVALID_SOCKET;
static Slot g_slots[MAX_SLOTS];
static std::atomic<int> g_slotCount{0};
static uintptr_t g_base = 0;

static Msg g_ring[RING_SIZE];
static std::atomic<uint32_t> g_head{0}; // consumer: next message to send
static std::atomic<uint32_t> g_tail{0}; // producer: next free slot
static std::atomic<uint32_t> g_seq{0};  // per-raise sequence, so Java sees ring drops as gaps
static HANDLE g_dataEvent = nullptr;    // signalled when the ring gains a message

// One generic detour per possible slot; filled with &detourK<0..MAX_SLOTS-1>. MinHook needs a
// distinct function per target, and every raiser shares one four-register fastcall shape.
static void *g_detours[MAX_SLOTS];

static void installSlot(Slot slot);
static void sendStatus(uint16_t requested);

/**
 * Appends one capture-plan value to a message body, dereferencing the argument register per the op.
 * Dereferences are SEH-guarded, so a bad pointer drops the value instead of crashing the client.
 *
 * @param out  the message body to append to
 * @param off  the current write offset, advanced past the value written
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
 * Builds a message for one raise and pushes it into the ring. Runs on the game thread, so it only
 * captures and enqueues; the writer thread does the socket work. A full ring drops the message, and
 * the sequence number still advances so Java sees the gap.
 *
 * @param slot the hooked raiser
 * @param id   the resolved event id
 * @param regs the four captured argument registers
 */
static void enqueue(const Slot &slot, int32_t id, const uint64_t regs[4]) {
    uint32_t seq = g_seq.fetch_add(1, std::memory_order_relaxed);

    uint8_t body[MAX_MSG];
    int off = 0;
    std::memcpy(body + off, &seq, 4); off += 4;
    std::memcpy(body + off, &id, 4); off += 4;
    std::memcpy(body + off, regs, 32); off += 32;
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

    uint32_t tail = g_tail.load(std::memory_order_relaxed);
    uint32_t head = g_head.load(std::memory_order_acquire);
    if (tail - head >= RING_SIZE) {
        return; // ring full: drop (the sequence gap tells Java)
    }
    Msg &msg = g_ring[tail & RING_MASK];
    msg.len = static_cast<uint32_t>(off);
    std::memcpy(msg.data, body, off);
    g_tail.store(tail + 1, std::memory_order_release);
    if (g_dataEvent) {
        SetEvent(g_dataEvent);
    }
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
 * Reads the handshake and installs any hooks not installed yet. The handshake is: magic "KRK2", a
 * u16 slot count, then each slot's rva, id configuration, argument count and capture plan. A slot
 * whose rva is already hooked is left alone, so a re-handshake after a reconnect only adds new ones.
 *
 * @return true if the handshake parsed; false if the connection closed mid-handshake
 */
static bool readHandshake() {
    char magic[4];
    if (!recvExact(magic, 4) || std::memcmp(magic, "KRK2", 4) != 0) {
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
    sendStatus(count);
    return true;
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
 * Sends a status message so Java can log how many of the requested hooks installed.
 *
 * @param requested the number of slots the handshake asked for
 */
static void sendStatus(uint16_t requested) {
    uint64_t regs[4] = {static_cast<uint64_t>(g_slotCount.load(std::memory_order_acquire)), requested, 0, 0};
    uint8_t body[MAX_MSG];
    int off = 0;
    uint32_t seq = g_seq.fetch_add(1, std::memory_order_relaxed);
    int32_t id = STATUS_EVENT_ID;
    std::memcpy(body + off, &seq, 4); off += 4;
    std::memcpy(body + off, &id, 4); off += 4;
    std::memcpy(body + off, regs, 32); off += 32;
    body[off++] = 0;
    uint32_t len = static_cast<uint32_t>(off);
    sendExact(reinterpret_cast<const char *>(&len), 4);
    sendExact(reinterpret_cast<const char *>(body), off);
}

/**
 * Drains the ring to the socket until a send fails, framing each message with its 4-byte length.
 *
 * @return false when the socket breaks and the connection should be re-established
 */
static bool drainRing() {
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
 * The writer thread: connect, read the handshake, drain the ring to the socket, and reconnect for
 * the life of the process if the socket drops. Hooks stay installed across reconnects; a dropped
 * socket costs the client latency, not events, because the Java side keeps polling as a fallback.
 */
static DWORD WINAPI writerThread(LPVOID) {
    bool first = true;
    for (;;) {
        if (!connectToJava(first ? 50 : 10)) {
            Sleep(1000);
            continue;
        }
        first = false;
        if (readHandshake()) {
            drainRing();
        }
        if (g_sock != INVALID_SOCKET) {
            closesocket(g_sock);
            g_sock = INVALID_SOCKET;
        }
        Sleep(1000); // the writer reconnects every second, then Java re-sends the handshake
    }
}

/**
 * Worker started off the loader lock: resolve the module base, set up MinHook and the detour table,
 * then run the writer thread. Returns non-zero on any setup failure.
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
    fillDetours(std::make_index_sequence<MAX_SLOTS>{});
    g_dataEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (g_dataEvent == nullptr || MH_Initialize() != MH_OK) {
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
