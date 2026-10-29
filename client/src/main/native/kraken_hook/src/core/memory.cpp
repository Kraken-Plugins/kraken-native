#include "core/memory.h"

#include <cstring>

#include "core/guard.h"

namespace kraken::memory {

bool safeRead(uint64_t address, void *out, size_t n) {
    if (address < 0x10000) {
        return false;
    }
    bool faulted = false;
    GUARD_BEGIN std::memcpy(out, reinterpret_cast<const void *>(address), n); GUARD_END
    return !faulted;
}

int32_t readI32(uint64_t address, int32_t fallback) {
    int32_t value = 0;
    return safeRead(address, &value, 4) ? value : fallback;
}

uint64_t readU64(uint64_t address, uint64_t fallback) {
    uint64_t value = 0;
    return safeRead(address, &value, 8) ? value : fallback;
}

bool readPointer(uint64_t address, uint64_t *out) {
    bool faulted = false;
    uint64_t value = 0;
    GUARD_BEGIN value = *reinterpret_cast<const volatile uint64_t *>(address); GUARD_END
    *out = value;
    return !faulted;
}

} // namespace kraken::memory
