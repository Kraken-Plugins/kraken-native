// Client function calls. The reader thread queues the batches CALLS commands send; the pump runs them
// on the game thread at the start of the next frame, each batch with a scratch block its calls can
// share, so a sequence of calls can build one object. Each batch the queue takes is answered with
// CALLS_DONE.
#pragma once

#include <cstdint>

#include "core/frame_reader.h"

namespace kraken::calls {

/**
 * Sets the slot CLIENT and INDIRECT arguments read the Client* from, as the handshake names it.
 *
 * @param rva the slot's rva, 0 for none
 */
void setClientGlobal(uint64_t rva);

/**
 * The Client* the handshake's clientGlobal slot holds now, for modules that read the client's objects.
 *
 * @return the Client*, or 0 if the slot is unknown, unreadable or null
 */
uint64_t clientPointer();

/**
 * Parses a CALLS command and queues it for the pump. A malformed frame, or a batch that would take
 * the queue past its call limit, is still queued with every call counted as skipped, so CALLS_DONE
 * answers it. A batch that arrives while the queue already holds its limit of batches is dropped
 * without an answer. Nothing is queued while no pump is hooked: nothing would run it.
 *
 * @param body the frame's body after the type byte
 */
void queue(FrameReader body);

/**
 * Runs every queued call batch, in order, and reports each with CALLS_DONE. Game thread only, in the
 * pump detour.
 */
void runPending();

/**
 * Drops every queued call, as when the connection that sent them closes.
 */
void clear();

} // namespace kraken::calls
