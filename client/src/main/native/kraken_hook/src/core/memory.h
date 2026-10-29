// Guarded reads of the game's memory: a bad address makes a read fail instead of crashing the client.
#pragma once

#include <cstddef>
#include <cstdint>

namespace kraken::memory {

/**
 * Copies bytes out of the game's memory, guarding the read.
 *
 * @param address where to read
 * @param out     receives the bytes
 * @param n       how many
 * @return false if the address is null or the read faulted
 */
bool safeRead(uint64_t address, void *out, size_t n);

/**
 * Reads an int32 from the game's memory.
 *
 * @param address  where to read
 * @param fallback the value if the read fails
 * @return the value, or the fallback
 */
int32_t readI32(uint64_t address, int32_t fallback);

/**
 * Reads a 64-bit value from the game's memory.
 *
 * @param address  where to read
 * @param fallback the value if the read fails
 * @return the value, or the fallback
 */
uint64_t readU64(uint64_t address, uint64_t fallback);

/**
 * Reads a pointer-sized value from the game's memory, guarding the read.
 *
 * @param address where to read
 * @param out     receives the value, 0 if the read faulted
 * @return false if the read faulted
 */
bool readPointer(uint64_t address, uint64_t *out);

} // namespace kraken::memory
