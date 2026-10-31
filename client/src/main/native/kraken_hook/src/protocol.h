// The wire format shared with the Kraken client: protocol v8, little-endian. Every value here must match
// NativeProtocol, NativeEventServer and GameSurface on the Java side, so change them together.
// docs/developer/native-protocol.md describes the format, and docs/developer/in-game-drawing.md the
// overlay surface.
#pragma once

#include <cstddef>
#include <cstdint>

namespace kraken {

// Must match NativeEventServer.PORT on the Java side.
inline constexpr unsigned short KRAKEN_PORT = 42069;

inline constexpr uint32_t PROTOCOL_VERSION = 8;
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
// MENU_CLICK: regs = {CLICK_ROW or CLICK_RULE, the row id or the rule id, the parent row id (NO_ROW
// for a top row or a rule), mouse x | mouse y << 32}. MENU_EXPIRED: regs = {rule id, 0, 0, 0}.
// KEY: regs = {message, the key or character it carried, what was done (KEY_*) << 16 | the key it
// became, mode before | mode after << 8}.
// PRESENT: regs = {presents so far, 0, 0, 0}; sent when the game presented a frame since the last
// PRESENT, while the overlay draws a surface, so Java draws the overlay's next frame.
// STATUS, FRAME, FPS and PRESENT go straight from the writer thread and carry sequence number 0.
inline constexpr int32_t STATUS_EVENT_ID = -2;
inline constexpr int32_t FRAME_EVENT_ID = -3;
inline constexpr int32_t CALLS_DONE_EVENT_ID = -4;
inline constexpr int32_t FPS_EVENT_ID = -5;
inline constexpr int32_t SCRIPT_EVENT_ID = -6;
inline constexpr int32_t MENU_CLICK_EVENT_ID = -7;
inline constexpr int32_t MENU_EXPIRED_EVENT_ID = -8;
inline constexpr int32_t KEY_EVENT_ID = -9;
inline constexpr int32_t PRESENT_EVENT_ID = -10;

// STATUS feature bits. FRAME_RATE: FPS is sent, and FRAME_LIMIT is honoured while the pump is hooked.
// SCRIPTS: the four script hooks are live, and SCRIPTS commands are honoured. MENU: the minimenu
// hooks are live, and MENU commands are honoured. KEYS: the window procedure is hooked, and KEYS
// commands are honoured. VISIBILITY: the pump is hooked and the handshake named the entity layout, so
// VISIBILITY commands are honoured. COMPONENTS: the pump is hooked and the handshake named the
// interface store, so COMPONENTS commands are honoured.
inline constexpr uint64_t FEATURE_PUMP = 1;
inline constexpr uint64_t FEATURE_PRESENT = 2;
inline constexpr uint64_t FEATURE_FRAME_RATE = 4;
inline constexpr uint64_t FEATURE_SCRIPTS = 8;
inline constexpr uint64_t FEATURE_MENU = 16;
inline constexpr uint64_t FEATURE_KEYS = 32;
inline constexpr uint64_t FEATURE_VISIBILITY = 64;
inline constexpr uint64_t FEATURE_COMPONENTS = 128;

// Command frames (Java -> DLL): {u32 len, u8 type, body}, len counting the type byte and the body.
inline constexpr uint8_t CMD_CALLS = 1;
inline constexpr uint8_t CMD_SURFACE = 2;
inline constexpr uint8_t CMD_FRAME_LIMIT = 3;
inline constexpr uint8_t CMD_SCRIPTS = 4;
inline constexpr uint8_t CMD_MENU = 5;
inline constexpr uint8_t CMD_KEYS = 6;
inline constexpr uint8_t CMD_VISIBILITY = 7;
inline constexpr uint8_t CMD_COMPONENTS = 8;
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

// MENU command operations: replace every rule, add or replace rules by id, remove rules by id.
inline constexpr uint8_t MENU_REPLACE = 0;
inline constexpr uint8_t MENU_UPSERT = 1;
inline constexpr uint8_t MENU_REMOVE = 2;

// Menu rule types and flags. EDIT changes the rows it matches; SWAP moves a row another row's
// presence asks for (MES-style swaps); ADD puts a row of Kraken's own next to the rows it matches.
inline constexpr uint8_t RULE_EDIT = 1;
inline constexpr uint8_t RULE_SWAP = 2;
inline constexpr uint8_t RULE_ADD = 3;
inline constexpr uint8_t RULE_SHIFT_DOWN = 1; // only while Shift is held
inline constexpr uint8_t RULE_SHIFT_UP = 2;   // only while Shift isn't held
inline constexpr uint8_t RULE_SCOPED = 4;     // expires once nothing matches it for MENU_GRACE rebuilds
inline constexpr uint8_t RULE_FINAL = 8;      // a row it matches takes no swaps from later rules

// What a row match compares; the text modes compare the row's text with colour tags removed and
// ASCII lower-cased against a pattern sent the same way.
inline constexpr uint16_t MATCH_KIND = 1;
inline constexpr uint16_t MATCH_TYPE_ID = 2;
inline constexpr uint16_t MATCH_INDEX = 4;
inline constexpr uint16_t MATCH_OP = 8;
inline constexpr uint16_t MATCH_TILE = 16;
inline constexpr uint16_t MATCH_OPTION = 32;
inline constexpr uint16_t MATCH_TARGET = 64;
inline constexpr uint16_t MATCH_SUB_ROWS = 128; // match the rows of a component op's sub-menu instead
inline constexpr uint16_t MATCH_GROUP = 256;    // a component's interface: type id >> 16
inline constexpr uint8_t TEXT_ANY = 0;
inline constexpr uint8_t TEXT_EXACT = 1;
inline constexpr uint8_t TEXT_CONTAINS = 2;
inline constexpr uint8_t TEXT_PREFIX = 3;
inline constexpr uint8_t TEXT_SUFFIX = 4;
inline constexpr uint8_t TEXT_WILDCARD = 5;

// EDIT actions.
inline constexpr uint8_t EDIT_HIDE = 1;
inline constexpr uint8_t EDIT_DEPRIORITIZE = 2;
inline constexpr uint8_t EDIT_PROMOTE = 4;         // swap with the top row (a sub-menu row: copy it to the top)
inline constexpr uint8_t EDIT_PROMOTE_SUBJECT = 8; // swap with the highest of the rows above it on the same subject
inline constexpr uint8_t EDIT_SET_OPTION = 16;
inline constexpr uint8_t EDIT_SET_TARGET = 32;
inline constexpr uint8_t EDIT_REPORT = 64;         // report a click on the row as MENU_CLICK; the row still runs

// SWAP flags: which row the swapped match must be, and where it goes.
inline constexpr uint8_t SWAP_SAME_TARGET = 1;  // its target text equals the trigger's
inline constexpr uint8_t SWAP_SAME_SUBJECT = 2; // same subject kind, type id, index and tile
inline constexpr uint8_t SWAP_BELOW = 4;        // below the trigger
inline constexpr uint8_t SWAP_TO_TOP = 8;       // swap with the top row, not with the trigger

// ADD positions.
inline constexpr uint8_t ADD_ABOVE = 0;
inline constexpr uint8_t ADD_BELOW = 1;
inline constexpr uint8_t ADD_TOP = 2;

// A Kraken row's entry: subject kind 0, this op index, its row id as the type id and its parent's
// row id (NO_ROW for a top row) as the subject index. Must match MenuEdits.ROW_OP on the Java side.
inline constexpr int32_t MENU_ROW_OP = 0x4b524b4e; // "NKRK"
inline constexpr uint32_t NO_ROW = 0xffffffffu;
inline constexpr int MENU_GRACE = 25; // rebuilds a scoped rule may go unmatched before it expires
inline constexpr uint64_t CLICK_ROW = 0;  // MENU_CLICK kinds: a row of Kraken's own, a rule's EDIT_REPORT
inline constexpr uint64_t CLICK_RULE = 1;

// KEYS command operations: the mode table, the mode in force, whether to report keys.
inline constexpr uint8_t KEYS_TABLE = 0;
inline constexpr uint8_t KEYS_MODE = 1;
inline constexpr uint8_t KEYS_REPORT = 2;
inline constexpr uint8_t KEY_MODE_BLOCK_TYPING = 1; // drop typed printable characters in this mode
inline constexpr uint8_t KEY_SHIFT = 1;             // key rule modifier bits
inline constexpr uint8_t KEY_CTRL = 2;
inline constexpr uint8_t KEY_ALT = 4;
inline constexpr uint16_t KEY_TO_BLOCK = 0xffff;    // a rule's key that drops the key
inline constexpr uint8_t KEY_STAY = 0xff;           // a rule's next mode that keeps the mode
inline constexpr int MAX_KEY_MODES = 8;
inline constexpr uint64_t KEY_PASSED = 0;  // KEY report actions
inline constexpr uint64_t KEY_REMAPPED = 1;
inline constexpr uint64_t KEY_BLOCKED = 2;

// VISIBILITY command: what to hide. The class bits say which kinds of entity are hidden unless an
// override says otherwise; an override names an actor by address, which layers it decides (LAYER_*)
// and which of those it hides. 3D is the model, 2D the actor's name, head bars and overhead text.
inline constexpr uint8_t HIDE_LOCAL_3D = 1;
inline constexpr uint8_t HIDE_LOCAL_2D = 2;
inline constexpr uint8_t HIDE_PLAYERS_3D = 4;
inline constexpr uint8_t HIDE_PLAYERS_2D = 8;
inline constexpr uint8_t HIDE_NPCS_3D = 16;
inline constexpr uint8_t HIDE_NPCS_2D = 32;
inline constexpr uint8_t HIDE_PROJECTILES = 64;
inline constexpr uint8_t LAYER_3D = 1;
inline constexpr uint8_t LAYER_2D = 2;
inline constexpr int MAX_VISIBILITY_OVERRIDES = 4096;
inline constexpr int MAX_HIDDEN_GRAPHICS = 64; // graphics object spot anim ids a command can hide

// COMPONENTS command ops: write an int32 field of the component, clear one of its listeners
// through the client's own function, which frees the listener's arguments, or set one of its
// strings as cc_settext does (the op's value is the text's length, its bytes follow the op).
inline constexpr uint8_t COMPONENT_SET_I32 = 0;
inline constexpr uint8_t COMPONENT_CLEAR_LISTENER = 1;
inline constexpr uint8_t COMPONENT_SET_TEXT = 2;
inline constexpr int MAX_COMPONENT_OPS = 4096; // ops waiting for the pump at most
inline constexpr int MAX_COMPONENT_TEXT = 4096; // bytes of a COMPONENT_SET_TEXT text at most

// Overlay surface layout (docs/developer/in-game-drawing.md, "The Overlay Surface").
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
