// The wire format shared with the Kraken client: protocol v5, little-endian. Every value here must match
// NativeProtocol, NativeEventServer and GameSurface on the Java side, so change them together. README.md
// describes the format, and docs/in-game-drawing.md section 2.4 the overlay surface.
#pragma once

#include <cstddef>
#include <cstdint>

namespace kraken {

// Must match NativeEventServer.PORT on the Java side.
inline constexpr unsigned short KRAKEN_PORT = 42069;

inline constexpr uint32_t PROTOCOL_VERSION = 5;
inline constexpr int MAX_PLAN = 8;  // capture-plan entries per slot
inline constexpr int CSTR_MAX = 64; // longest NUL-terminated string captured

// Capture-plan ops: how to read the argument register named by the plan entry.
inline constexpr uint8_t OP_RAW = 0;   // the register value itself (u64)
inline constexpr uint8_t OP_I32 = 1;   // int32 at [register]
inline constexpr uint8_t OP_I64 = 2;   // int64 at [register]
inline constexpr uint8_t OP_I32X2 = 3; // two int32 at [register]
inline constexpr uint8_t OP_CSTR = 4;  // NUL-terminated bytes at [register], at most CSTR_MAX

// Reserved message ids. STATUS: regs = {installed slots, requested slots, features, protocol}.
// FRAME: regs = {framebuffer width, height, GL version (major << 16 | minor), surface generation}.
// CALLS_DONE: regs = {batch id, calls run, calls faulted or skipped, last return value}.
// FPS: regs = {frames since the last FPS, microseconds since it, frame cap in force, 1 if presents
// were counted (0: frame steps)}.
// SCRIPT: regs = {kind (SCRIPT_PRE_ROOT, SCRIPT_PRE_GOSUB or SCRIPT_POST), script id, frame depth,
// 0}, then the stacks and the request (see sendScript in scripts.cpp).
inline constexpr int32_t STATUS_EVENT_ID = -2;
inline constexpr int32_t FRAME_EVENT_ID = -3;
inline constexpr int32_t CALLS_DONE_EVENT_ID = -4;
inline constexpr int32_t FPS_EVENT_ID = -5;
inline constexpr int32_t SCRIPT_EVENT_ID = -6;

// STATUS feature bits. FRAME_RATE: FPS is sent, and FRAME_LIMIT is honoured while the pump is hooked.
// SCRIPTS: the four script hooks are live, and SCRIPTS commands are honoured.
inline constexpr uint64_t FEATURE_PUMP = 1;
inline constexpr uint64_t FEATURE_PRESENT = 2;
inline constexpr uint64_t FEATURE_FRAME_RATE = 4;
inline constexpr uint64_t FEATURE_SCRIPTS = 8;

// Command frames (Java -> DLL): {u32 len, u8 type, body}, len counting the type byte and the body.
inline constexpr uint8_t CMD_CALLS = 1;
inline constexpr uint8_t CMD_SURFACE = 2;
inline constexpr uint8_t CMD_FRAME_LIMIT = 3;
inline constexpr uint8_t CMD_SCRIPTS = 4;
inline constexpr uint32_t MAX_FRAME_LIMIT = 1000; // a higher cap is no cap
inline constexpr uint32_t MAX_FRAME = 4u << 20;   // bytes per command frame
inline constexpr int MAX_ARGS = 8;                // arguments per call

// Call argument kinds.
inline constexpr uint8_t ARG_IMM = 0;      // the value
inline constexpr uint8_t ARG_CLIENT = 1;   // the Client* read from base + clientGlobal, plus the value
inline constexpr uint8_t ARG_DATA = 2;     // a pointer to data + value
inline constexpr uint8_t ARG_SCRATCH = 3;  // a pointer to the batch's scratch block + value
inline constexpr uint8_t ARG_INDIRECT = 4; // the pointer at Client* + (value & 0xffffffff), plus (value >> 32)
inline constexpr uint32_t SCRATCH_SIZE = 4096; // bytes of zeroed scratch a batch's calls share

// SCRIPTS command bits: which script events to send. The flags byte is followed by a u8 count and
// that many i32 script ids, the root scripts SCRIPT_CAPTURE_ROOTS asks for.
inline constexpr uint8_t SCRIPT_CAPTURE_PRE = 1;   // root runs and gosubs as they start
inline constexpr uint8_t SCRIPT_CAPTURE_POST = 2;  // returns
inline constexpr uint8_t SCRIPT_CAPTURE_ROOTS = 4; // root runs of the listed scripts as they start
inline constexpr int MAX_SCRIPT_ROOTS = 8;         // root scripts a SCRIPTS command can list

// SCRIPT message kinds (regs[0]).
inline constexpr uint64_t SCRIPT_PRE_ROOT = 0;
inline constexpr uint64_t SCRIPT_PRE_GOSUB = 1;
inline constexpr uint64_t SCRIPT_POST = 2;

// How a script message encodes a VarValue: a kind byte, then an int32, a u16 length and the bytes,
// an int64, or nothing.
inline constexpr uint8_t VALUE_OTHER = 0;
inline constexpr uint8_t VALUE_INT = 1;
inline constexpr uint8_t VALUE_STRING = 2;
inline constexpr uint8_t VALUE_LONG = 3;

// A VarValue kind the handshake leaves unnamed.
inline constexpr uint8_t NO_KIND = 0xff;

// Overlay surface layout (docs/in-game-drawing.md section 2.4).
inline constexpr uint32_t SURFACE_MAGIC = 0x31564F4B; // "KOV1"
inline constexpr size_t SURFACE_PUBLISHED = 16;
inline constexpr size_t SURFACE_READING = 20;
inline constexpr size_t SURFACE_SLOT_HEADERS = 64;
inline constexpr size_t SURFACE_SLOT_HEADER_SIZE = 16;
inline constexpr size_t SURFACE_PIXELS = 128;
inline constexpr uint32_t SURFACE_MAX_SLOTS = 3;
inline constexpr uint32_t SURFACE_MAX_SIDE = 16384;
inline constexpr uint32_t PUBLISHED_NONE = 0xffffffffu;

} // namespace kraken
