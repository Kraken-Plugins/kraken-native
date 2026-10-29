// The message ring: the game thread queues messages in it and the writer thread sends them, so a slow
// socket never stalls the game. Records are {u32 length, body} (the wire framing). The ring has a
// single producer, the game thread (raise and script detours, and the pump), and a single consumer,
// the writer thread.
#pragma once

#include <cstdint>

namespace kraken::ring {

/**
 * A function that sends bytes to the socket.
 *
 * @param data the bytes
 * @param len  how many
 * @return false on any socket error
 */
using SendFn = bool (*)(const char *data, int len);

/**
 * Creates the event that wakes the writer thread. Call once, before any hook is installed.
 *
 * @return false if the event could not be created
 */
bool init();

/**
 * Writes the fixed message head (sequence, id, four registers) into a body. Every message takes the
 * next sequence number, whether it goes through the ring or straight to the socket.
 *
 * @param body where to write; at least 40 bytes
 * @param id   the event or reserved id
 * @param regs the four register values
 * @return the offset just past the head
 */
int writeHead(uint8_t *body, int32_t id, const uint64_t regs[4]);

/**
 * Pushes one finished message body into the ring as a {u32 length, body} record. Only the game
 * thread may call this: the ring has a single producer. A full ring drops the message; its sequence
 * number was already taken, so Java sees the gap.
 *
 * @param body the message body, starting with its sequence number
 * @param len  the body length in bytes
 */
void push(const uint8_t *body, int len);

/**
 * Wakes the writer thread, as when the connection closes or the frame changes.
 */
void wakeWriter();

/**
 * Sends every queued record, oldest first, each already a length frame and its body. Writer thread
 * only.
 *
 * @param send sends one record
 * @return false if a send failed
 */
bool sendQueued(SendFn send);

/**
 * Waits up to a second for the game thread to queue a record or for wakeWriter, returning at once
 * if a record arrived since the last sendQueued. Writer thread only.
 */
void waitForData();

} // namespace kraken::ring
