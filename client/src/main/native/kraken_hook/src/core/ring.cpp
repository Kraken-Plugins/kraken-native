#include "core/ring.h"

#include <windows.h>

#include <atomic>
#include <cstring>

namespace kraken::ring {
namespace {

constexpr uint32_t RING_BYTES = 1u << 22;  // bytes of queued messages; a full ring drops new ones (power of two)
constexpr uint32_t RING_MASK = RING_BYTES - 1;
constexpr uint32_t RING_PAD = 0xffffffffu; // a record length meaning the rest of the ring is unused

// Each record starts on a 4-byte boundary and never wraps; a RING_PAD length marks the unused end of
// the ring. Positions are byte counts that only grow, taken modulo RING_BYTES.
uint8_t g_ring[RING_BYTES];
std::atomic<uint32_t> g_head{0};          // consumer: position of the next record to send
std::atomic<uint32_t> g_tail{0};          // producer: position of the next free byte
std::atomic<uint32_t> g_seq{0};           // per-message sequence, so Java sees ring drops as gaps
HANDLE g_dataEvent = nullptr;             // signalled when the ring gains a message or the frame changes
std::atomic<bool> g_writerWaiting{false}; // the writer thread is about to wait on g_dataEvent

} // namespace

bool init() {
    g_dataEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    return g_dataEvent != nullptr;
}

int writeHead(uint8_t *body, int32_t id, const uint64_t regs[4]) {
    uint32_t seq = g_seq.fetch_add(1, std::memory_order_relaxed);
    int off = 0;
    std::memcpy(body + off, &seq, 4); off += 4;
    std::memcpy(body + off, &id, 4); off += 4;
    std::memcpy(body + off, regs, 32); off += 32;
    return off;
}

void push(const uint8_t *body, int len) {
    // A record that doesn't fit before the end of the ring starts over at its beginning, behind a
    // RING_PAD marker. The writer thread is woken only when it is about to wait, so a burst of
    // messages costs no system call each.
    uint32_t record = (4u + static_cast<uint32_t>(len) + 3u) & ~3u;
    uint32_t tail = g_tail.load(std::memory_order_relaxed);
    uint32_t head = g_head.load(std::memory_order_acquire);
    uint32_t pos = tail & RING_MASK;
    uint32_t pad = record > RING_BYTES - pos ? RING_BYTES - pos : 0;
    if (RING_BYTES - (tail - head) < pad + record) {
        return; // ring full: drop (the sequence gap tells Java)
    }
    if (pad != 0) {
        std::memcpy(g_ring + pos, &RING_PAD, 4);
        tail += pad;
        pos = 0;
    }
    uint32_t length = static_cast<uint32_t>(len);
    std::memcpy(g_ring + pos, &length, 4);
    std::memcpy(g_ring + pos + 4, body, len);
    // seq_cst on both sides: either the writer's re-check sees this record or this sees it waiting
    g_tail.store(tail + record, std::memory_order_seq_cst);
    if (g_dataEvent && g_writerWaiting.load(std::memory_order_seq_cst)
            && g_writerWaiting.exchange(false, std::memory_order_seq_cst)) {
        SetEvent(g_dataEvent);
    }
}

void wakeWriter() {
    if (g_dataEvent) {
        SetEvent(g_dataEvent);
    }
}

bool sendQueued(SendFn send) {
    uint32_t head = g_head.load(std::memory_order_relaxed);
    uint32_t tail = g_tail.load(std::memory_order_acquire);
    while (head != tail) {
        uint32_t pos = head & RING_MASK;
        uint32_t length = 0;
        std::memcpy(&length, g_ring + pos, 4);
        if (length == RING_PAD) {
            head += RING_BYTES - pos;
        } else {
            if (!send(reinterpret_cast<const char *>(g_ring + pos), static_cast<int>(4 + length))) {
                return false;
            }
            head += (4u + length + 3u) & ~3u;
        }
        g_head.store(head, std::memory_order_release);
        tail = g_tail.load(std::memory_order_acquire);
    }
    return true;
}

void waitForData() {
    // Only this thread moves the head, so this is where the last sendQueued left it
    uint32_t head = g_head.load(std::memory_order_relaxed);
    g_writerWaiting.store(true, std::memory_order_seq_cst);
    if (g_tail.load(std::memory_order_seq_cst) == head) {
        WaitForSingleObject(g_dataEvent, 1000);
    }
    g_writerWaiting.store(false, std::memory_order_seq_cst);
}

} // namespace kraken::ring
