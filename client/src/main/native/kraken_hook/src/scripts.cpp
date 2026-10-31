#include "scripts.h"

#include <atomic>
#include <cstdint>
#include <cstring>

#include "core/hooks.h"
#include "core/memory.h"
#include "core/process.h"
#include "core/ring.h"
#include "protocol.h"

namespace kraken::scripts {
namespace {

using memory::readI32;
using memory::readU64;
using memory::safeRead;

constexpr int MAX_SCRIPT_MSG = 16384; // bytes per script message body

// What a script message holds at most: the top of each stack, the request's arguments, and a
// string's bytes.
constexpr int MAX_SCRIPT_DEPTH = 64;
constexpr int MAX_STACK_INTS = 64;
constexpr int MAX_STACK_OBJS = 16;
constexpr int MAX_REQUEST_ARGS = 24;
constexpr int MAX_SCRIPT_STRING = 256;

constexpr uint32_t NONE = 0xffffffffu; // a runner offset the mapping lacks

/**
 * Where the script runtime keeps what a script event reports, from the handshake: the four hooked
 * functions, the static runner and its stacks, a frame's script slot, a script's id, the VarValue
 * layout and kinds, the listener request's fields, a component's id and index, the eastl string
 * layout, and the runner's active and dot components. RVAs are image-relative; the rest are byte
 * offsets.
 */
struct ScriptLayout {
    uint64_t executeScript = 0;      // runs a listener's request: (runner, request, maxOps, int)
    uint64_t executeScriptInner = 0; // the interpreter loop: (runner, script slot, maxOps, int)
    uint64_t scriptLookup = 0;       // the script with an id: (id); the gosub calls it with the callee's
    uint64_t scriptSwapLocals = 0;   // (frame, ints, longs, objects); a return calls it on a cleared frame
    uint64_t runner = 0;             // the static runner
    uint32_t intStack = 0;
    uint32_t intStackSize = 0;
    uint32_t objStack = 0;
    uint32_t objStackSize = 0;
    uint32_t frameCount = 0;
    uint32_t frameScript = 0;        // a frame's shared_ptr<ClientScript>
    uint32_t sharedPtr = 0;          // the pointer's offset in a shared_ptr
    uint32_t scriptId = 0;
    uint32_t valueSize = 0;
    uint32_t valueType = 0;
    uint8_t kindInt = NO_KIND;
    uint8_t kindString = NO_KIND;
    uint8_t kindLong = NO_KIND;
    uint32_t argCount = 0;           // the request's fields
    uint32_t args = 0;
    uint32_t source = 0;
    uint32_t target = 0;
    uint32_t op = 0;
    uint32_t subOp = 0;
    uint32_t mouseX = 0;
    uint32_t mouseY = 0;
    uint32_t keyCode = 0;
    uint32_t keyChar = 0;
    uint32_t opbase = 0;
    uint32_t componentId = 0;        // a component's packed id and its index under its static parent
    uint32_t componentIndex = 0;
    uint32_t stringFlagByte = 0;     // eastl string: the heap form {char* at +0, u64 size at +8} when set
    uint32_t stringFlagMask = 0;
    uint32_t stringSsoCapacity = 0;
    uint32_t activeComponent = NONE; // the runner's shared_ptr<IfType> that cc_* ops act on
    uint32_t dotComponent = NONE;    // and the one .cc_* ops act on
};

/**
 * A writer over the script message being built.
 */
struct ScriptWriter {
    uint8_t *buf;
    int off;

    bool room(int n) const {
        return off + n <= MAX_SCRIPT_MSG;
    }

    void raw(const void *src, int n) {
        std::memcpy(buf + off, src, n);
        off += n;
    }

    template<typename T>
    void put(T value) {
        raw(&value, sizeof(T));
    }
};

ScriptLayout g_scripts;                  // written once, before the script hooks install
bool g_scriptLayoutSet = false;          // handshake thread only
std::atomic<uint8_t> g_scriptCapture{0}; // SCRIPT_CAPTURE_* bits from the last SCRIPTS command
std::atomic<int32_t> g_scriptRoots[MAX_SCRIPT_ROOTS]; // the root scripts SCRIPT_CAPTURE_ROOTS asks for
std::atomic<int> g_scriptRootCount{0};
void *g_executeScriptTrampoline = nullptr;
void *g_interpreterTrampoline = nullptr;
void *g_lookupTrampoline = nullptr;
void *g_swapTrampoline = nullptr;
std::atomic<bool> g_executeScriptHooked{false};
std::atomic<bool> g_interpreterHooked{false};
std::atomic<bool> g_lookupHooked{false};
std::atomic<bool> g_swapHooked{false};

// Game thread only.
const uint8_t *g_request = nullptr;    // the request being run, until the interpreter takes it
int g_interpreterDepth = 0;            // interpreter runs on the stack
int64_t g_scriptIds[MAX_SCRIPT_DEPTH]; // the running scripts' ids by frame depth
uint8_t g_scriptBody[MAX_SCRIPT_MSG];  // the script message being built

/**
 * Reads an eastl string's bytes, at most MAX_SCRIPT_STRING of them.
 *
 * @param address the string object
 * @param out     receives the bytes, MAX_SCRIPT_STRING long
 * @return how many bytes were read; 0 for an empty or unreadable string
 */
int readString(uint64_t address, uint8_t *out) {
    const ScriptLayout &l = g_scripts;
    uint8_t flag = 0;
    if (!safeRead(address + l.stringFlagByte, &flag, 1)) {
        return 0;
    }
    uint64_t text = address;
    uint64_t length = 0;
    if ((flag & l.stringFlagMask) != 0) {
        text = readU64(address, 0);
        length = readU64(address + 8, 0);
    } else if (l.stringSsoCapacity >= flag) {
        length = l.stringSsoCapacity - flag;
    }
    int n = length > static_cast<uint64_t>(MAX_SCRIPT_STRING) ? MAX_SCRIPT_STRING : static_cast<int>(length);
    return n > 0 && safeRead(text, out, static_cast<size_t>(n)) ? n : 0;
}

/**
 * Appends one VarValue: a VALUE_* kind, then an int32, a u16 length and the string's bytes, an int64,
 * or nothing for a kind the mapping doesn't name. The caller checks there is room for the largest.
 *
 * @param w       the message
 * @param address the VarValue
 */
void appendValue(ScriptWriter &w, uint64_t address) {
    const ScriptLayout &l = g_scripts;
    uint8_t kind = NO_KIND;
    safeRead(address + l.valueType, &kind, 1);
    if (kind == NO_KIND) {
        w.put<uint8_t>(VALUE_OTHER);
    } else if (kind == l.kindInt) {
        w.put<uint8_t>(VALUE_INT);
        w.put<int32_t>(readI32(address, 0));
    } else if (kind == l.kindString) {
        uint8_t text[MAX_SCRIPT_STRING];
        int n = readString(address, text);
        w.put<uint8_t>(VALUE_STRING);
        w.put<uint16_t>(static_cast<uint16_t>(n));
        w.raw(text, n);
    } else if (kind == l.kindLong) {
        w.put<uint8_t>(VALUE_LONG);
        w.put<int64_t>(static_cast<int64_t>(readU64(address, 0)));
    } else {
        w.put<uint8_t>(VALUE_OTHER);
    }
}

/**
 * Appends a run of VarValues: a u16 count, then each one. Stops early, with the count saying how many
 * went in, when the message has no room for another.
 *
 * @param w      the message
 * @param first  the first VarValue
 * @param count  how many follow it, VarValue size apart
 */
void appendValues(ScriptWriter &w, uint64_t first, int count) {
    int countAt = w.off;
    w.put<uint16_t>(0);
    uint16_t written = 0;
    for (int i = 0; i < count && w.room(3 + MAX_SCRIPT_STRING); i++) {
        appendValue(w, first + static_cast<uint64_t>(i) * g_scripts.valueSize);
        written++;
    }
    std::memcpy(w.buf + countAt, &written, 2);
}

/**
 * Appends the top of both stacks: the int stack's size, the number of ints sent and those ints
 * (oldest first), then the same for the object stack. The top MAX_STACK_INTS and MAX_STACK_OBJS
 * entries are sent.
 *
 * @param w the message
 */
void appendStacks(ScriptWriter &w) {
    const ScriptLayout &l = g_scripts;
    uint64_t runner = process::base() + l.runner;
    int32_t size = readI32(runner + l.intStackSize, 0);
    int n = size < 0 ? 0 : (size > MAX_STACK_INTS ? MAX_STACK_INTS : size);
    int32_t ints[MAX_STACK_INTS] = {};
    if (n > 0 && !safeRead(runner + l.intStack + 4ull * static_cast<uint64_t>(size - n), ints, 4u * n)) {
        n = 0;
    }
    w.put<int32_t>(size);
    w.put<uint16_t>(static_cast<uint16_t>(n));
    w.raw(ints, 4 * n);

    int32_t objects = readI32(runner + l.objStackSize, 0);
    int m = objects < 0 ? 0 : (objects > MAX_STACK_OBJS ? MAX_STACK_OBJS : objects);
    w.put<int32_t>(objects);
    appendValues(w, runner + l.objStack + static_cast<uint64_t>(objects - m) * l.valueSize, m);
}

/**
 * Appends a component's packed id and index, or -1 twice when there is none.
 *
 * @param w         the message
 * @param component the component, or 0
 */
void appendComponent(ScriptWriter &w, uint64_t component) {
    const ScriptLayout &l = g_scripts;
    w.put<int32_t>(component == 0 ? -1 : readI32(component + l.componentId, -1));
    w.put<int32_t>(component == 0 ? -1 : readI32(component + l.componentIndex, -1));
}

/**
 * One of the runner's components: the object behind its shared pointer at an offset.
 *
 * @param offset the shared pointer's offset in the runner, or NONE
 * @return the component, or 0 for none
 */
uint64_t runnerComponent(uint32_t offset) {
    if (offset == NONE) {
        return 0;
    }
    return readU64(process::base() + g_scripts.runner + offset + g_scripts.sharedPtr, 0);
}

/**
 * Appends the request a root run came from: a 0 byte for none, else 1, the source and target
 * components, the op, sub-op, mouse x and y, key code and key char, the base op name and the
 * listener's arguments as it holds them (the script's id first, placeholders unreplaced).
 *
 * @param w       the message
 * @param request the request, or nullptr
 */
void appendRequest(ScriptWriter &w, const uint8_t *request) {
    if (request == nullptr) {
        w.put<uint8_t>(0);
        return;
    }
    const ScriptLayout &l = g_scripts;
    uint64_t r = reinterpret_cast<uint64_t>(request);
    w.put<uint8_t>(1);
    appendComponent(w, readU64(r + l.source, 0));
    appendComponent(w, readU64(r + l.target, 0));
    const uint32_t fields[] = {l.op, l.subOp, l.mouseX, l.mouseY, l.keyCode, l.keyChar};
    for (uint32_t field : fields) {
        w.put<int32_t>(readI32(r + field, -1));
    }
    uint8_t text[MAX_SCRIPT_STRING];
    int n = readString(r + l.opbase, text);
    w.put<uint16_t>(static_cast<uint16_t>(n));
    w.raw(text, n);
    int32_t count = readI32(r + l.argCount, 0);
    int k = count < 0 ? 0 : (count > MAX_REQUEST_ARGS ? MAX_REQUEST_ARGS : count);
    uint64_t args = readU64(r + l.args, 0);
    appendValues(w, args, args == 0 ? 0 : k);
}

/**
 * Builds a SCRIPT message and pushes it into the ring: the head (kind, id, depth), no plan values,
 * then the stacks, the runner's active and dot components, and the request. Game thread only.
 *
 * @param kind    SCRIPT_PRE_ROOT, SCRIPT_PRE_GOSUB or SCRIPT_POST
 * @param id      the script's id
 * @param depth   its frame depth (0 for a root script)
 * @param request the request a root run came from, or nullptr
 */
void sendScript(uint64_t kind, int64_t id, int depth, const uint8_t *request) {
    ScriptWriter w{g_scriptBody, 0};
    uint64_t regs[4] = {kind, static_cast<uint64_t>(id), static_cast<uint64_t>(depth), 0};
    w.off = ring::writeHead(w.buf, SCRIPT_EVENT_ID, regs);
    w.put<uint8_t>(0);
    appendStacks(w);
    appendComponent(w, runnerComponent(g_scripts.activeComponent));
    appendComponent(w, runnerComponent(g_scripts.dotComponent));
    appendRequest(w, request);
    ring::push(w.buf, w.off);
}

/**
 * The frames the runner has pushed, which is the running script's depth.
 *
 * @return the frame count, or -1 if it can't be read
 */
int frameDepth() {
    return readI32(process::base() + g_scripts.runner + g_scripts.frameCount, -1);
}

/**
 * Marks the request a request run carries for the interpreter run it starts, and puts the outer one
 * back when it ends, however it ends.
 */
struct RequestScope {
    const uint8_t *outer;

    explicit RequestScope(const uint8_t *request) : outer(g_request) {
        g_request = request;
    }

    ~RequestScope() {
        g_request = outer;
    }
};

/**
 * Counts an interpreter run as on the stack for as long as it runs, however it ends.
 */
struct InterpreterScope {
    InterpreterScope() {
        g_interpreterDepth++;
    }

    ~InterpreterScope() {
        g_interpreterDepth--;
    }
};

/**
 * The request entry's detour: keeps the request for the interpreter run it starts.
 *
 * @param runner  the runner
 * @param request the listener's request
 * @param maxOps  the op limit
 * @param d       the fourth argument
 * @return whatever the original returns
 */
int64_t __fastcall executeScriptDetour(int64_t runner, int64_t request, int64_t maxOps, int64_t d) {
    RequestScope scope(reinterpret_cast<const uint8_t *>(request));
    using Fn = int64_t(__fastcall *)(int64_t, int64_t, int64_t, int64_t);
    return reinterpret_cast<Fn>(g_executeScriptTrampoline)(runner, request, maxOps, d);
}

/**
 * The interpreter's detour: notes the root script's id at depth 0, reports the run as it starts
 * (with the request if a request run started it) and again when it ends. A run that stops on an
 * error ends here too, so its end is reported like a return.
 *
 * @param runner the runner
 * @param slot   the shared_ptr holding the root script
 * @param maxOps the op limit
 * @param d      the fourth argument
 * @return whatever the original returns
 */
/**
 * Whether a root script is one the last SCRIPTS command listed. The list can change on the reader
 * thread while this runs; a script checked against a list half replaced is reported or not, never
 * misreported.
 *
 * @param id the script's id
 * @return true if it is listed
 */
bool listedRoot(int64_t id) {
    int count = g_scriptRootCount.load(std::memory_order_acquire);
    for (int i = 0; i < count && i < MAX_SCRIPT_ROOTS; i++) {
        if (g_scriptRoots[i].load(std::memory_order_relaxed) == id) {
            return true;
        }
    }
    return false;
}

int64_t __fastcall interpreterDetour(int64_t runner, int64_t slot, int64_t maxOps, int64_t d) {
    const uint8_t *request = g_request;
    g_request = nullptr;
    uint64_t script = readU64(static_cast<uint64_t>(slot) + g_scripts.sharedPtr, 0);
    int64_t id = script == 0 ? -1 : static_cast<int64_t>(readU64(script + g_scripts.scriptId, ~0ull));
    g_scriptIds[0] = id;
    using Fn = int64_t(__fastcall *)(int64_t, int64_t, int64_t, int64_t);
    int64_t ret;
    {
        InterpreterScope scope;
        uint8_t capture = g_scriptCapture.load(std::memory_order_relaxed);
        if ((capture & SCRIPT_CAPTURE_PRE) != 0 || ((capture & SCRIPT_CAPTURE_ROOTS) != 0 && listedRoot(id))) {
            sendScript(SCRIPT_PRE_ROOT, id, 0, request);
        }
        ret = reinterpret_cast<Fn>(g_interpreterTrampoline)(runner, slot, maxOps, d);
    }
    if ((g_scriptCapture.load(std::memory_order_relaxed) & SCRIPT_CAPTURE_POST) != 0) {
        sendScript(SCRIPT_POST, id, 0, nullptr);
    }
    return ret;
}

/**
 * The script lookup's detour. Inside an interpreter run it is a gosub: note the callee's id at the
 * depth it will run at and report it, before the gosub pops its arguments. The request entry's own
 * lookup, before its run starts, passes through.
 *
 * @param id the script's id
 * @param b  the second argument register
 * @param c  the third
 * @param d  the fourth
 * @return whatever the original returns
 */
int64_t __fastcall lookupDetour(int64_t id, int64_t b, int64_t c, int64_t d) {
    if (g_interpreterDepth > 0) {
        int depth = frameDepth() + 1;
        int32_t callee = static_cast<int32_t>(id);
        if (depth > 0 && depth < MAX_SCRIPT_DEPTH) {
            g_scriptIds[depth] = callee;
        }
        if ((g_scriptCapture.load(std::memory_order_relaxed) & SCRIPT_CAPTURE_PRE) != 0) {
            sendScript(SCRIPT_PRE_GOSUB, callee, depth, nullptr);
        }
    }
    using Fn = int64_t(__fastcall *)(int64_t, int64_t, int64_t, int64_t);
    return reinterpret_cast<Fn>(g_lookupTrampoline)(id, b, c, d);
}

/**
 * The locals swap's detour. Called on a frame whose script slot is cleared, it is a return: the
 * returning script ran one deeper than the frames left, and its return values are on the stacks.
 *
 * @param frame the frame
 * @param b     the runner's int locals
 * @param c     its long locals
 * @param d     its object locals
 * @return whatever the original returns
 */
int64_t __fastcall swapDetour(int64_t frame, int64_t b, int64_t c, int64_t d) {
    if (g_interpreterDepth > 0 && (g_scriptCapture.load(std::memory_order_relaxed) & SCRIPT_CAPTURE_POST) != 0
            && readU64(static_cast<uint64_t>(frame) + g_scripts.frameScript + g_scripts.sharedPtr, 1) == 0) {
        int depth = frameDepth() + 1;
        int64_t id = depth > 0 && depth < MAX_SCRIPT_DEPTH ? g_scriptIds[depth] : -1;
        sendScript(SCRIPT_POST, id, depth, nullptr);
    }
    using Fn = int64_t(__fastcall *)(int64_t, int64_t, int64_t, int64_t);
    return reinterpret_cast<Fn>(g_swapTrampoline)(frame, b, c, d);
}

/**
 * Parses the handshake's script block into a layout, in ScriptLayout's field order.
 *
 * @param r   the block
 * @param out receives the layout
 * @return false if the block is too short
 */
bool parseScriptLayout(FrameReader r, ScriptLayout &out) {
    uint64_t *rvas[] = {&out.executeScript, &out.executeScriptInner, &out.scriptLookup, &out.scriptSwapLocals,
                        &out.runner};
    uint32_t *offsets[] = {&out.intStack, &out.intStackSize, &out.objStack, &out.objStackSize, &out.frameCount,
                           &out.frameScript, &out.sharedPtr, &out.scriptId, &out.valueSize, &out.valueType};
    uint8_t *kinds[] = {&out.kindInt, &out.kindString, &out.kindLong};
    uint32_t *fields[] = {&out.argCount, &out.args, &out.source, &out.target, &out.op, &out.subOp, &out.mouseX,
                          &out.mouseY, &out.keyCode, &out.keyChar, &out.opbase, &out.componentId,
                          &out.componentIndex, &out.stringFlagByte, &out.stringFlagMask, &out.stringSsoCapacity,
                          &out.activeComponent, &out.dotComponent};
    for (uint64_t *rva : rvas) {
        if (!r.get(*rva)) {
            return false;
        }
    }
    for (uint32_t *offset : offsets) {
        if (!r.get(*offset)) {
            return false;
        }
    }
    for (uint8_t *kind : kinds) {
        if (!r.get(*kind)) {
            return false;
        }
    }
    for (uint32_t *field : fields) {
        if (!r.get(*field)) {
            return false;
        }
    }
    return true;
}

/**
 * Hooks the script runtime's four functions. The interpreter goes in last: the other detours only
 * report while an interpreter run they saw start is on the stack, so a script already running while
 * they install is passed over rather than half reported.
 *
 * @param layout the functions and layout the handshake sent
 */
void installScripts(const ScriptLayout &layout) {
    if (!g_scriptLayoutSet) {
        if (layout.executeScript == 0 || layout.executeScriptInner == 0 || layout.scriptLookup == 0
                || layout.scriptSwapLocals == 0 || !process::inImage(layout.runner) || layout.valueSize == 0) {
            return;
        }
        g_scripts = layout;
        g_scriptLayoutSet = true;
    }
    hooks::installOnce(g_scripts.scriptLookup, reinterpret_cast<void *>(&lookupDetour), &g_lookupTrampoline,
                       g_lookupHooked);
    hooks::installOnce(g_scripts.scriptSwapLocals, reinterpret_cast<void *>(&swapDetour), &g_swapTrampoline,
                       g_swapHooked);
    hooks::installOnce(g_scripts.executeScript, reinterpret_cast<void *>(&executeScriptDetour),
                       &g_executeScriptTrampoline, g_executeScriptHooked);
    hooks::installOnce(g_scripts.executeScriptInner, reinterpret_cast<void *>(&interpreterDetour),
                       &g_interpreterTrampoline, g_interpreterHooked);
}

} // namespace

void install(FrameReader block) {
    ScriptLayout layout;
    if (parseScriptLayout(block, layout)) {
        installScripts(layout);
    }
}

bool hooked() {
    return g_executeScriptHooked.load(std::memory_order_acquire) && g_interpreterHooked.load(std::memory_order_acquire)
           && g_lookupHooked.load(std::memory_order_acquire) && g_swapHooked.load(std::memory_order_acquire);
}

void setCapture(FrameReader r) {
    uint8_t flags = 0;
    if (!r.get(flags) || !hooked()) {
        return;
    }
    uint8_t listed = 0;
    int count = 0;
    g_scriptRootCount.store(0, std::memory_order_release);
    if (r.get(listed)) {
        for (int32_t id = 0; count < listed && count < MAX_SCRIPT_ROOTS && r.get(id); count++) {
            g_scriptRoots[count].store(id, std::memory_order_relaxed);
        }
    }
    g_scriptRootCount.store(count, std::memory_order_release);
    g_scriptCapture.store(flags & (SCRIPT_CAPTURE_PRE | SCRIPT_CAPTURE_POST | SCRIPT_CAPTURE_ROOTS),
                          std::memory_order_relaxed);
}

void stopCapture() {
    g_scriptCapture.store(0, std::memory_order_relaxed);
    g_scriptRootCount.store(0, std::memory_order_release);
}

} // namespace kraken::scripts
