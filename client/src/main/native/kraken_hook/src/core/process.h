// The process this DLL runs in: where osclient.exe's image lies, and the performance counter's rate.
// Resolved once by init, before any hook is installed, and only read after.
#pragma once

#include <cstdint>

namespace kraken::process {

/**
 * Resolves osclient.exe's base and image size and the performance counter's frequency.
 *
 * @return false if osclient.exe is not loaded in this process
 */
bool init();

/**
 * Where osclient.exe is loaded.
 *
 * @return the module base; RVAs are relative to it
 */
uintptr_t base();

/**
 * How many bytes osclient.exe's image spans from its base.
 *
 * @return the image size
 */
uint64_t imageSize();

/**
 * Whether an RVA lies inside osclient.exe's image.
 *
 * @param rva the RVA
 * @return true if it is non-zero and below the image size
 */
bool inImage(uint64_t rva);

/**
 * The performance counter's ticks per second.
 *
 * @return the frequency, or 0 before init
 */
int64_t counterFrequency();

} // namespace kraken::process
