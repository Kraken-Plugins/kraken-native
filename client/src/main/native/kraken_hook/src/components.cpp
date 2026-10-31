#include "components.h"

#include <windows.h>

#include <atomic>
#include <cstdint>
#include <utility>
#include <vector>

#include "calls.h"
#include "core/guard.h"
#include "core/memory.h"
#include "core/process.h"
#include "protocol.h"
#include "pump.h"

namespace kraken::components {
namespace {

using memory::readI32;
using memory::readU64;
using memory::safeWrite;

/**
 * Where the interface store keeps components, from the handshake, in the block's order. The store
 * holds a group entry per interface, each an array of slots, one per static component; a static
 * component holds its dynamic children's slots. A slot is a shared pointer, the object at slotObject.
 */
struct Layout {
    uint64_t listenerClear = 0;  // the client's IfListener clear: (IfListener*), 0 for none
    uint32_t interfaceStore = 0; // Client: the IfStore*
    uint32_t groupCount = 0;     // IfStore: the number of group entries and the entry array
    uint32_t groups = 0;
    uint32_t groupStride = 0;    // a group entry's size, its slot count and its slot array
    uint32_t slotCount = 0;
    uint32_t slots = 0;
    uint32_t slotStride = 0;     // a slot's size, and where in it the component pointer is
    uint32_t slotObject = 0;
    uint32_t dynCount = 0;       // IfType: the dynamic children's slot count and slot array
    uint32_t dynSlots = 0;
    uint32_t packedId = 0;       // IfType: the packed id and the dynamic child index (-1 when static)
    uint32_t subId = 0;
};

/**
 * One op: which component, what to do, at which offset in it, and the value to write.
 */
struct Op {
    int32_t packedId = 0;
    int32_t subId = -1;
    uint8_t op = 0;
    uint16_t offset = 0;
    int32_t value = 0;
};

Layout g_layout;                  // written once, before g_ready
std::atomic<bool> g_ready{false};

SRWLOCK g_queueLock = SRWLOCK_INIT;
std::vector<Op> g_queue;          // guarded by g_queueLock
std::atomic<uint32_t> g_pending{0};

/**
 * Looks a component up as the client does: the group entry, the static component's slot, then for a
 * dynamic child the child's slot under it, and checks the object found carries the ids asked for.
 *
 * @param client   the Client*
 * @param packedId the component's group << 16 | component
 * @param subId    its index among the static component's dynamic children, or -1 for the static one
 * @return the component, or 0 if the client holds none there
 */
uint64_t find(uint64_t client, int32_t packedId, int32_t subId) {
    const Layout &l = g_layout;
    uint64_t store = readU64(client + l.interfaceStore, 0);
    uint32_t group = static_cast<uint32_t>(packedId) >> 16;
    uint32_t child = static_cast<uint32_t>(packedId) & 0xffffu;
    if (store == 0 || group >= readU64(store + l.groupCount, 0)) {
        return 0;
    }
    uint64_t entry = readU64(store + l.groups, 0);
    if (entry == 0) {
        return 0;
    }
    entry += static_cast<uint64_t>(group) * l.groupStride;
    uint64_t slots = readU64(entry + l.slots, 0);
    if (slots == 0 || child >= readU64(entry + l.slotCount, 0)) {
        return 0;
    }
    uint64_t component = readU64(slots + static_cast<uint64_t>(child) * l.slotStride + l.slotObject, 0);
    if (component != 0 && subId >= 0) {
        uint64_t dyn = readU64(component + l.dynSlots, 0);
        if (dyn == 0 || static_cast<uint64_t>(subId) >= readU64(component + l.dynCount, 0)) {
            return 0;
        }
        component = readU64(dyn + static_cast<uint64_t>(subId) * l.slotStride + l.slotObject, 0);
    }
    if (component == 0 || readI32(component + l.packedId, -1) != packedId) {
        return 0;
    }
    int32_t index = readI32(component + l.subId, -2);
    return (subId >= 0 ? index == subId : index < 0) ? component : 0;
}

/**
 * Calls the client's listener clear on one listener slot under an SEH guard.
 *
 * @param target the function's address
 * @param slot   the listener slot
 * @return false if the call faulted
 */
bool guardedClear(uintptr_t target, uint64_t slot) {
    using Fn = void(__fastcall *)(uint64_t);
    Fn fn = reinterpret_cast<Fn>(target);
    bool faulted = false;
    GUARD_BEGIN
        fn(slot);
    GUARD_END
    return !faulted;
}

/**
 * Runs one op on the component it names, if the client holds it.
 *
 * @param client the Client*
 * @param op     the op
 */
void run(uint64_t client, const Op &op) {
    uint64_t component = find(client, op.packedId, op.subId);
    if (component == 0) {
        return;
    }
    switch (op.op) {
        case COMPONENT_SET_I32:
            safeWrite(component + op.offset, &op.value, sizeof(op.value));
            break;
        case COMPONENT_CLEAR_LISTENER:
            if (g_layout.listenerClear != 0) {
                guardedClear(process::base() + g_layout.listenerClear, component + op.offset);
            }
            break;
        default:
            break;
    }
}

} // namespace

void install(FrameReader r) {
    if (g_ready.load(std::memory_order_acquire)) {
        return;
    }
    Layout l;
    uint32_t *fields[] = {&l.interfaceStore, &l.groupCount, &l.groups, &l.groupStride, &l.slotCount, &l.slots,
                          &l.slotStride, &l.slotObject, &l.dynCount, &l.dynSlots, &l.packedId, &l.subId};
    if (!r.get(l.listenerClear)) {
        return;
    }
    for (uint32_t *field : fields) {
        if (!r.get(*field)) {
            return;
        }
    }
    if (l.groupStride == 0 || l.slotStride == 0 || (l.listenerClear != 0 && !process::inImage(l.listenerClear))) {
        return;
    }
    g_layout = l;
    g_ready.store(true, std::memory_order_release);
}

bool live() {
    return g_ready.load(std::memory_order_acquire) && pump::hooked();
}

void queue(FrameReader r) {
    if (!live()) {
        return;
    }
    uint16_t count = 0;
    if (!r.get(count)) {
        return;
    }
    std::vector<Op> ops;
    ops.reserve(count < r.left / 15 ? count : r.left / 15); // an op is 15 bytes
    for (uint16_t i = 0; i < count; i++) {
        Op op;
        if (!r.get(op.packedId) || !r.get(op.subId) || !r.get(op.op) || !r.get(op.offset) || !r.get(op.value)) {
            return; // a malformed command queues nothing
        }
        ops.push_back(op);
    }
    AcquireSRWLockExclusive(&g_queueLock);
    for (const Op &op : ops) {
        if (g_queue.size() >= static_cast<size_t>(MAX_COMPONENT_OPS)) {
            break;
        }
        g_queue.push_back(op);
    }
    g_pending.store(static_cast<uint32_t>(g_queue.size()), std::memory_order_release);
    ReleaseSRWLockExclusive(&g_queueLock);
}

void runPending() {
    // A reader thread holding the queue costs a frame's delay, not a stall
    if (g_pending.load(std::memory_order_acquire) == 0 || !TryAcquireSRWLockExclusive(&g_queueLock)) {
        return;
    }
    std::vector<Op> work;
    work.swap(g_queue);
    g_pending.store(0, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_queueLock);
    uint64_t client = calls::clientPointer();
    if (client == 0) {
        return;
    }
    for (const Op &op : work) {
        run(client, op);
    }
}

void clear() {
    std::vector<Op> dropped;
    AcquireSRWLockExclusive(&g_queueLock);
    dropped.swap(g_queue);
    g_pending.store(0, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_queueLock);
}

} // namespace kraken::components
