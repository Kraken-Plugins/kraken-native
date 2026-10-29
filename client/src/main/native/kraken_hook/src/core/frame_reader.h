// A bounds-checked little-endian reader over a command frame or a handshake block.
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace kraken {

/**
 * A bounds-checked little-endian reader over one command frame.
 */
struct FrameReader {
    const uint8_t *p;
    size_t left;

    template<typename T>
    bool get(T &out) {
        if (left < sizeof(T)) {
            return false;
        }
        std::memcpy(&out, p, sizeof(T));
        p += sizeof(T);
        left -= sizeof(T);
        return true;
    }

    bool bytes(uint8_t *out, size_t n) {
        if (left < n) {
            return false;
        }
        if (n > 0) {
            std::memcpy(out, p, n);
        }
        p += n;
        left -= n;
        return true;
    }
};

} // namespace kraken
