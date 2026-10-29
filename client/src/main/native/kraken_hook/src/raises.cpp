#include "raises.h"

#include <MinHook.h>

#include <atomic>
#include <cstring>
#include <utility>

#include "core/guard.h"
#include "core/process.h"
#include "core/ring.h"

namespace kraken::raises {
namespace {

constexpr int MAX_SLOTS = 64; // distinct raise functions we can hook
constexpr int MAX_MSG = 512;  // bytes per raise message body

Slot g_slots[MAX_SLOTS];
std::atomic<int> g_slotCount{0};

// One generic detour per possible slot; filled with &detourK<0..MAX_SLOTS-1>. MinHook needs a
// distinct function per target, and every raiser shares one four-register fastcall shape.
void *g_detours[MAX_SLOTS];

/**
 * Appends one capture-plan value to a message body, dereferencing the argument register per the op.
 * Dereferences are SEH-guarded, so a bad pointer drops the value instead of crashing the client.
 *
 * @param out   the message body to append to
 * @param off   the current write offset, advanced past the value written
 * @param entry the plan entry (which register, which op)
 * @param regs  the four captured argument registers
 */
void appendValue(uint8_t *out, int &off, const PlanEntry &entry, const uint64_t regs[4]) {
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
 * captures and enqueues; the writer thread does the socket work.
 *
 * @param slot the hooked raiser
 * @param id   the resolved event id
 * @param regs the four captured argument registers
 */
void enqueue(const Slot &slot, int32_t id, const uint64_t regs[4]) {
    uint8_t body[MAX_MSG];
    int off = ring::writeHead(body, id, regs);
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
    ring::push(body, off);
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
int64_t handle(int k, int64_t a, int64_t b, int64_t c, int64_t d) {
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
int64_t __fastcall detourK(int64_t a, int64_t b, int64_t c, int64_t d) {
    return handle(K, a, b, c, d);
}

template<std::size_t... I>
void fillDetours(std::index_sequence<I...>) {
    ((g_detours[I] = reinterpret_cast<void *>(&detourK<I>)), ...);
}

} // namespace

void init() {
    fillDetours(std::make_index_sequence<MAX_SLOTS>{});
}

void install(Slot slot) {
    int n = g_slotCount.load(std::memory_order_acquire);
    for (int i = 0; i < n; i++) {
        if (g_slots[i].rva == slot.rva) {
            return; // already hooked; leave it in place for the life of the process
        }
    }
    if (n >= MAX_SLOTS) {
        return;
    }
    slot.target = reinterpret_cast<void *>(process::base() + slot.rva);
    g_slots[n] = slot;
    if (MH_CreateHook(g_slots[n].target, g_detours[n], &g_slots[n].trampoline) != MH_OK
            || MH_EnableHook(g_slots[n].target) != MH_OK) {
        return; // leave the slot count unchanged; this target simply never pushes
    }
    g_slotCount.store(n + 1, std::memory_order_release);
}

int installedCount() {
    return g_slotCount.load(std::memory_order_acquire);
}

} // namespace kraken::raises
