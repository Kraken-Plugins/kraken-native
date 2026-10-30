#include "calls.h"

#include <windows.h>

#include <algorithm>
#include <atomic>
#include <utility>
#include <vector>

#include "core/guard.h"
#include "core/memory.h"
#include "core/process.h"
#include "core/ring.h"
#include "protocol.h"
#include "pump.h"

namespace kraken::calls {
namespace {

constexpr uint32_t MAX_PENDING_CALLS = 4096;
constexpr uint32_t MAX_PENDING_BATCHES = 4096;

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

// The Client* slot, from the handshake.
std::atomic<uint64_t> g_clientGlobal{0};

// The call queue: filled by the reader thread, drained by the pump on the game thread.
SRWLOCK g_queueLock = SRWLOCK_INIT;
std::vector<Batch> g_queue;
std::atomic<uint32_t> g_pendingBatches{0};
uint32_t g_pendingCalls = 0; // guarded by g_queueLock

/**
 * Reads the Client* the handshake's clientGlobal slot holds, guarding the read.
 *
 * @return the Client*, or 0 if the slot is unknown, unreadable or null
 */
uint64_t readClient() {
    uint64_t slot = g_clientGlobal.load(std::memory_order_relaxed);
    if (!process::inImage(slot)) {
        return 0;
    }
    uint64_t client = 0;
    memory::readPointer(process::base() + slot, &client);
    return client;
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
bool guardedCall(uintptr_t target, const int64_t args[MAX_ARGS], int64_t *ret) {
    using Fn = int64_t(__fastcall *)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t, int64_t);
    Fn fn = reinterpret_cast<Fn>(target);
    bool faulted = false;
    GUARD_BEGIN
        *ret = fn(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
    GUARD_END
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
int runCall(Call &call, uint8_t *scratch, int64_t *ret) {
    if (!process::inImage(call.rva) || call.argc > MAX_ARGS) {
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
                if (!memory::readPointer(client + offset, &pointer) || pointer == 0) {
                    return 0;
                }
                args[i] = static_cast<int64_t>(pointer + static_cast<uint64_t>(add));
                break;
            }
            default:
                return 0;
        }
    }
    return guardedCall(process::base() + call.rva, args, ret) ? 1 : -1;
}

} // namespace

void setClientGlobal(uint64_t rva) {
    g_clientGlobal.store(rva, std::memory_order_relaxed);
}

uint64_t clientPointer() {
    return readClient();
}

void runPending() {
    // Takes the queue with a try-lock, so a reader thread that holds it costs one frame's delay rather
    // than a stall. Each batch gets a fresh, zeroed scratch block its calls can build an object in and
    // hand on to each other.
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
        int off = ring::writeHead(body, CALLS_DONE_EVENT_ID, regs);
        body[off++] = 0;
        ring::push(body, off);
    }
}

void clear() {
    std::vector<Batch> dropped;
    AcquireSRWLockExclusive(&g_queueLock);
    dropped.swap(g_queue);
    g_pendingCalls = 0;
    g_pendingBatches.store(0, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_queueLock);
}

void queue(FrameReader r) {
    if (!pump::hooked()) {
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

} // namespace kraken::calls
