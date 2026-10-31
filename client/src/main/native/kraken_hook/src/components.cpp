#include "components.h"

#include <windows.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>
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
using memory::safeRead;
using memory::safeWrite;

/**
 * Where the interface store keeps components, from the handshake, in the block's order. The store
 * holds a group entry per interface, each an array of slots, one per static component; a static
 * component holds its dynamic children's slots. A slot is a shared pointer, the object at slotObject.
 */
struct Layout {
    uint64_t listenerClear = 0;  // the client's IfListener clear: (IfListener*), 0 for none
    uint64_t textAssign = 0;     // the client's string assignment: (string*, first, last), 0 for none
    uint64_t invalidate = 0;     // the client's component invalidate: (Client*, slot*), 0 for none
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
    uint32_t stringFlagByte = 0; // eastl string: the byte holding the heap flag, the flag, the SSO capacity
    uint32_t stringFlagMask = 0;
    uint32_t stringSsoCapacity = 0;
};

/**
 * One op: which component, what to do, at which offset in it, and the value or text to write.
 */
struct Op {
    int32_t packedId = 0;
    int32_t subId = -1;
    uint8_t op = 0;
    uint16_t offset = 0;
    int32_t value = 0;
    std::string text; // COMPONENT_SET_TEXT's text, value bytes long
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
 * @param slot     receives the shared pointer the component was found in
 * @return the component, or 0 if the client holds none there
 */
uint64_t find(uint64_t client, int32_t packedId, int32_t subId, uint64_t &slot) {
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
    slot = slots + static_cast<uint64_t>(child) * l.slotStride;
    uint64_t component = readU64(slot + l.slotObject, 0);
    if (component != 0 && subId >= 0) {
        uint64_t dyn = readU64(component + l.dynSlots, 0);
        if (dyn == 0 || static_cast<uint64_t>(subId) >= readU64(component + l.dynCount, 0)) {
            return 0;
        }
        slot = dyn + static_cast<uint64_t>(subId) * l.slotStride;
        component = readU64(slot + l.slotObject, 0);
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
 * Calls the client's string assignment on one string under an SEH guard.
 *
 * @param target the function's address
 * @param string the string to assign to
 * @param first  the new text
 * @param last   one past its last byte
 * @return false if the call faulted
 */
bool guardedAssign(uintptr_t target, uint64_t string, const char *first, const char *last) {
    using Fn = void *(__fastcall *)(uint64_t, const char *, const char *);
    Fn fn = reinterpret_cast<Fn>(target);
    bool faulted = false;
    GUARD_BEGIN
        fn(string, first, last);
    GUARD_END
    return !faulted;
}

/**
 * Calls the client's component invalidate under an SEH guard.
 *
 * @param target the function's address
 * @param client the Client*
 * @param slot   the shared pointer holding the component
 * @return false if the call faulted
 */
bool guardedInvalidate(uintptr_t target, uint64_t client, uint64_t slot) {
    using Fn = void(__fastcall *)(uint64_t, uint64_t);
    Fn fn = reinterpret_cast<Fn>(target);
    bool faulted = false;
    GUARD_BEGIN
        fn(client, slot);
    GUARD_END
    return !faulted;
}

/**
 * Whether an eastl string holds a text: the SSO form in place, the heap form through its pointer.
 *
 * @param string the string object
 * @param text   the text
 * @return true if the string reads and holds exactly that text
 */
bool holds(uint64_t string, const std::string &text) {
    const Layout &l = g_layout;
    uint8_t flag = 0;
    if (!safeRead(string + l.stringFlagByte, &flag, 1)) {
        return false;
    }
    uint64_t data = string;
    uint64_t length = 0;
    if ((flag & l.stringFlagMask) != 0) {
        data = readU64(string, 0);
        length = readU64(string + 8, 0);
    } else if (l.stringSsoCapacity >= flag) {
        length = l.stringSsoCapacity - flag;
    } else {
        return false;
    }
    if (length != text.size()) {
        return false;
    }
    std::vector<char> held(text.size());
    return text.empty() || (safeRead(data, held.data(), held.size())
                            && std::memcmp(held.data(), text.data(), text.size()) == 0);
}

/**
 * Sets one of a component's strings as cc_settext does: assigns the text through the client's own
 * function unless the string already holds it, then invalidates the component, which flags it for
 * redrawing and tells its subscribers.
 *
 * @param client    the Client*
 * @param component the component
 * @param slot      the shared pointer holding it
 * @param op        the op, its offset naming the string
 */
void setText(uint64_t client, uint64_t component, uint64_t slot, const Op &op) {
    uint64_t string = component + op.offset;
    if (holds(string, op.text)) {
        return;
    }
    const char *first = op.text.data();
    if (guardedAssign(process::base() + g_layout.textAssign, string, first, first + op.text.size())) {
        guardedInvalidate(process::base() + g_layout.invalidate, client, slot);
    }
}

/**
 * Runs one op on the component it names, if the client holds it.
 *
 * @param client the Client*
 * @param op     the op
 */
void run(uint64_t client, const Op &op) {
    uint64_t slot = 0;
    uint64_t component = find(client, op.packedId, op.subId, slot);
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
        case COMPONENT_SET_TEXT:
            if (g_layout.textAssign != 0 && g_layout.invalidate != 0) {
                setText(client, component, slot, op);
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
    uint64_t *functions[] = {&l.listenerClear, &l.textAssign, &l.invalidate};
    uint32_t *fields[] = {&l.interfaceStore, &l.groupCount, &l.groups, &l.groupStride, &l.slotCount, &l.slots,
                          &l.slotStride, &l.slotObject, &l.dynCount, &l.dynSlots, &l.packedId, &l.subId,
                          &l.stringFlagByte, &l.stringFlagMask, &l.stringSsoCapacity};
    for (uint64_t *function : functions) {
        if (!r.get(*function) || (*function != 0 && !process::inImage(*function))) {
            return;
        }
    }
    for (uint32_t *field : fields) {
        if (!r.get(*field)) {
            return;
        }
    }
    if (l.groupStride == 0 || l.slotStride == 0 || l.stringFlagByte >= 24) {
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
    ops.reserve(count < r.left / 15 ? count : r.left / 15); // an op is at least 15 bytes
    for (uint16_t i = 0; i < count; i++) {
        Op op;
        if (!r.get(op.packedId) || !r.get(op.subId) || !r.get(op.op) || !r.get(op.offset) || !r.get(op.value)) {
            return; // a malformed command queues nothing
        }
        if (op.op == COMPONENT_SET_TEXT) {
            if (op.value < 0 || op.value > MAX_COMPONENT_TEXT || r.left < static_cast<size_t>(op.value)) {
                return;
            }
            op.text.resize(static_cast<size_t>(op.value));
            r.bytes(reinterpret_cast<uint8_t *>(op.text.data()), op.text.size());
        }
        ops.push_back(std::move(op));
    }
    AcquireSRWLockExclusive(&g_queueLock);
    for (Op &op : ops) {
        if (g_queue.size() >= static_cast<size_t>(MAX_COMPONENT_OPS)) {
            break;
        }
        g_queue.push_back(std::move(op));
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
