// Inline hooks on the client's functions, through MinHook. Hooks stay installed for the life of the
// process.
#pragma once

#include <atomic>
#include <cstdint>

namespace kraken::hooks {

/**
 * Initialises MinHook. Call once, before any hook is installed.
 *
 * @return false if MinHook failed to initialise
 */
bool init();

/**
 * Hooks a function once with a dedicated detour.
 *
 * @param rva        the function's rva, 0 to skip
 * @param detour     the detour
 * @param trampoline receives the original
 * @param hooked     set once the hook is live; a set flag skips the install
 */
void installOnce(uint64_t rva, void *detour, void **trampoline, std::atomic<bool> &hooked);

} // namespace kraken::hooks
