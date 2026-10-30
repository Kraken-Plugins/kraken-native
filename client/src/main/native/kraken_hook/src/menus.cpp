#include "menus.h"

#include <windows.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "calls.h"
#include "core/guard.h"
#include "core/hooks.h"
#include "core/memory.h"
#include "core/process.h"
#include "core/ring.h"
#include "protocol.h"

namespace kraken::menus {
namespace {

using memory::readU64;
using memory::safeRead;

constexpr int MAX_ROWS = 128;        // top rows of one menu considered
constexpr int MAX_SUB_ROWS = 32;     // rows of one sub-menu considered
constexpr int MAX_KRAKEN_ROWS = 64;  // rows of Kraken's own in one menu, top rows and sub rows together
constexpr int MAX_CHILDREN = 32;     // sub rows under one row of Kraken's own
constexpr int MAX_TEXT = 96;         // bytes of a row's text kept for matching, tags stripped
constexpr int MAX_PATTERN = 64;      // bytes of a rule's text pattern
constexpr int MAX_RULE_TEXT = 256;   // bytes of a text a rule writes
constexpr int MAX_RULES = 1024;      // rules kept; later ones are refused
constexpr int MAX_NODE = 64;         // bytes of one row node; the client's are 24
constexpr uint64_t MAX_LIST_INDEX = 4096; // an entry index in a list beyond this is a bad read
constexpr int MAX_STRING_TEXT = 256; // bytes read of one entry string
constexpr uint32_t MIN_BUFFER_OFFSET = 24; // an entry string's own buffer lies past its 24-byte header
constexpr uint32_t MAX_BUFFER_OFFSET = 64;
constexpr int MAX_TEXT_CAPACITY = 1024;    // an entry string's capacity beyond this is a bad read
constexpr int ALLOCATOR_BYTES = 8;         // the overflow allocator an entry string keeps after its pool pointer

/**
 * Where the minimenu keeps what the edits touch, from the handshake: the five hooked or called functions, the
 * minimenu behind the client, its rows, open view and last-run copy, the node and list layout, the entry layout,
 * the subject kinds and op values that name an entry's action, and the eastl string layout. RVAs are image-relative;
 * the rest are byte offsets or values.
 */
struct MenuLayout {
    uint64_t viewRebuild = 0;  // clears and rebuilds the rows: (view, source)
    uint64_t run = 0;          // runs the row at a path: (view, path, mouseX, mouseY, flag)
    uint64_t nodeDestroy = 0;  // frees a node's children: (&children)
    uint64_t nodeReserve = 0;  // grows a node vector: (vector, count)
    uint64_t textAssign = 0;   // assigns an entry string: (dst, src)
    uint32_t clientMinimenu = 0;
    uint32_t rows = 0;
    uint32_t open = 0;
    uint32_t clicked = 0;
    uint32_t nodeList = 0;
    uint32_t nodeIndex = 0;
    uint32_t nodeChildren = 0;
    uint32_t nodeSize = 0;
    uint32_t listEntries = 0;
    uint32_t entrySize = 0;
    uint32_t option = 0;
    uint32_t target = 0;
    uint32_t typeId = 0;
    uint32_t subjectIndex = 0;
    uint32_t opIndex = 0;
    uint32_t level = 0;
    uint32_t x = 0;
    uint32_t z = 0;
    uint32_t subjectType = 0;
    uint8_t kindTile = 0;
    uint8_t kindNpc = 0;
    uint8_t kindLoc = 0;
    uint8_t kindObj = 0;
    uint8_t kindPlayer = 0;
    uint8_t kindComponent = 0;
    int32_t opExamine = 0;
    int32_t opTarget = 0;
    uint32_t stringFlagByte = 0;
    uint32_t stringFlagMask = 0;
    uint32_t stringSsoCapacity = 0;
};

/**
 * A text pattern of a match: how to compare and what with, lower-cased and tag-free as the Kraken client sends it.
 */
struct Pattern {
    uint8_t mode = TEXT_ANY;
    uint8_t len = 0;
    char text[MAX_PATTERN + 1] = {};
};

/**
 * What a rule compares a row with: MATCH_* says which fields count.
 */
struct Match {
    uint16_t mask = 0;
    uint8_t kind = 0;
    int32_t typeId = 0;
    int32_t index = 0;
    int32_t op = 0;
    int32_t group = 0;
    int32_t level = 0;
    int32_t x = 0;
    int32_t z = 0;
    Pattern option;
    Pattern target;
};

/**
 * A row of Kraken's own an ADD rule puts in the menu, with the sub rows under it.
 */
struct AddedRow {
    uint32_t id = NO_ROW;
    std::string option;
    std::string target;
    std::vector<AddedRow> children;
};

/**
 * One rule, as the MENU command sent it, plus how many rebuilds it has gone unmatched.
 */
struct Rule {
    uint32_t id = 0;
    uint8_t type = 0;
    uint8_t flags = 0;
    Match match;
    uint8_t actions = 0;     // EDIT
    std::string option;
    std::string target;
    uint8_t swapFlags = 0;   // SWAP
    Match swapped;
    uint8_t position = 0;    // ADD
    AddedRow row;
    int unmatched = 0;
};

/**
 * One MENU command, parsed on the reader thread and applied on the game thread.
 */
struct Command {
    uint8_t op = MENU_REPLACE;
    std::vector<Rule> rules;
    std::vector<uint32_t> ids;
};

/**
 * One row as the client built it this rebuild: its node, its entry and what the entry says, with the texts
 * normalised for matching. Sub rows carry the top row they hang under.
 */
struct RowInfo {
    uint8_t node[MAX_NODE] = {};
    uint64_t entry = 0;
    int parent = -1;         // the top row a sub row hangs under, -1 for a top row
    uint8_t kind = 0;
    int32_t typeId = 0;
    int32_t index = 0;
    int32_t op = 0;
    int32_t level = 0;
    int32_t x = 0;
    int32_t z = 0;
    char option[MAX_TEXT + 1] = {};
    char target[MAX_TEXT + 1] = {};
    bool swapped = false;    // took a swap already, or a FINAL rule claimed it
    bool hidden = false;
    bool deprioritized = false;
    uint32_t reportRule = 0; // a rule whose EDIT_REPORT wants its clicks, 0 for none
};

/**
 * One slot of the rows vector as it will be written: a client row, a copy of a sub row, or a row of Kraken's own.
 */
struct Slot {
    int row = -1;      // index in g_rows, or -1
    int sub = -1;      // index in g_subRows for a copied sub row, or -1
    int kraken = -1;   // index in g_krakenRows, or -1
};

/**
 * A row of Kraken's own as laid out for this rebuild: which entry slot holds it and its sub rows.
 */
struct KrakenRow {
    uint32_t id = NO_ROW;
    int entrySlot = -1;
    int childCount = 0;
    int firstChildSlot = -1; // the entry slot of the first sub row; the rest follow it
};

/**
 * What a Kraken entry's strings are built like, read once from the client's last-run copy: where the string's own
 * buffer starts, how many characters it holds, and the overflow allocator's bytes.
 */
struct StringTemplate {
    uint32_t bufferOffset = 0;
    uint32_t capacity = 0;
    uint8_t allocator[ALLOCATOR_BYTES] = {};
};

MenuLayout g_layout;                       // written once, before the hooks install
bool g_layoutSet = false;                  // handshake thread only
std::atomic<bool> g_rebuildHooked{false};
std::atomic<bool> g_runHooked{false};
std::atomic<bool> g_destroyHooked{false};
void *g_rebuildTrampoline = nullptr;
void *g_runTrampoline = nullptr;
void *g_destroyTrampoline = nullptr;

// Commands from the reader thread, drained by the game thread at each rebuild.
SRWLOCK g_pendingLock = SRWLOCK_INIT;
std::vector<Command> g_pending;
std::atomic<uint32_t> g_pendingCount{0};

// Game thread only.
std::vector<Rule> g_rules;
RowInfo g_rows[MAX_ROWS];
int g_rowCount = 0;
RowInfo g_subRows[MAX_ROWS * 2]; // sub rows read this rebuild, across the top rows
int g_subRowCount = 0;
KrakenRow g_krakenRows[MAX_KRAKEN_ROWS];
int g_krakenCount = 0;
uint32_t g_reportByRow[MAX_ROWS]; // after the rebuild: the EDIT_REPORT rule of each final row, 0 for none
int g_finalCount = 0;
bool g_templateReady = false;
StringTemplate g_optionTemplate;
StringTemplate g_targetTemplate;

// The list and entries Kraken's rows show: the list block names the entry pool as its entries; a row's node names
// the list and its entry's slot. Each row with sub rows has a children vector of its own, whose nodes do the same.
uint8_t g_list[64];
uint8_t *g_entryPool = nullptr;       // MAX_KRAKEN_ROWS entries
uint8_t *g_childNodePool = nullptr;   // MAX_KRAKEN_ROWS nodes
uint64_t g_childVectors[MAX_KRAKEN_ROWS][3]; // {begin, end, capacity end}

/**
 * Whether a children vector object is one of Kraken's.
 *
 * @param vector the vector object's address
 * @return true if it lies in the children vector pool
 */
bool ownVector(uint64_t vector) {
    uint64_t first = reinterpret_cast<uint64_t>(&g_childVectors[0][0]);
    uint64_t last = reinterpret_cast<uint64_t>(&g_childVectors[MAX_KRAKEN_ROWS - 1][0]);
    return vector >= first && vector <= last;
}

/**
 * Whether a node shows an entry of Kraken's list.
 *
 * @param node the node's bytes
 * @return true for a row of Kraken's own
 */
bool ownNode(const uint8_t *node) {
    uint64_t list;
    std::memcpy(&list, node + g_layout.nodeList, 8);
    return list == reinterpret_cast<uint64_t>(g_list);
}

uint64_t nodeField(const uint8_t *node, uint32_t offset) {
    uint64_t value;
    std::memcpy(&value, node + offset, 8);
    return value;
}

void setNodeField(uint8_t *node, uint32_t offset, uint64_t value) {
    std::memcpy(node + offset, &value, 8);
}

/**
 * Lower-cases ASCII and drops {@code <...>} tags, keeping at most MAX_TEXT bytes.
 *
 * @param in  the text
 * @param n   its length
 * @param out receives the normalised text, NUL-terminated, MAX_TEXT + 1 long
 */
void normalise(const uint8_t *in, int n, char *out) {
    int o = 0;
    bool tag = false;
    for (int i = 0; i < n && o < MAX_TEXT; i++) {
        char c = static_cast<char>(in[i]);
        if (c == '<') {
            tag = true;
        } else if (c == '>' && tag) {
            tag = false;
        } else if (!tag) {
            out[o++] = c >= 'A' && c <= 'Z' ? static_cast<char>(c + 32) : c;
        }
    }
    out[o] = 0;
}

/**
 * Reads an eastl string's text: the SSO form in place, the heap form through its pointer (which may point into the
 * entry's own buffer).
 *
 * @param address the string object
 * @param out     receives the bytes, MAX_STRING_TEXT long
 * @return how many bytes were read; 0 for an empty or unreadable string
 */
int readString(uint64_t address, uint8_t *out) {
    const MenuLayout &l = g_layout;
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
    int n = length > static_cast<uint64_t>(MAX_STRING_TEXT) ? MAX_STRING_TEXT : static_cast<int>(length);
    return n > 0 && safeRead(text, out, static_cast<size_t>(n)) ? n : 0;
}

/**
 * Whether a glob pattern with {@code *} and {@code ?} matches a text.
 *
 * @param pattern the pattern
 * @param text    the text
 * @return true if it matches the whole text
 */
bool wildcard(const char *pattern, const char *text) {
    const char *star = nullptr;
    const char *back = nullptr;
    while (*text != 0) {
        if (*pattern == '*') {
            star = pattern++;
            back = text;
        } else if (*pattern == '?' || *pattern == *text) {
            pattern++;
            text++;
        } else if (star != nullptr) {
            pattern = star + 1;
            text = ++back;
        } else {
            return false;
        }
    }
    while (*pattern == '*') {
        pattern++;
    }
    return *pattern == 0;
}

/**
 * Whether a pattern matches a row's normalised text.
 *
 * @param p    the pattern
 * @param text the text
 * @return true if it matches in the pattern's mode
 */
bool matchText(const Pattern &p, const char *text) {
    size_t n = std::strlen(text);
    switch (p.mode) {
        case TEXT_EXACT:
            return n == p.len && std::memcmp(text, p.text, n) == 0;
        case TEXT_CONTAINS:
            return std::strstr(text, p.text) != nullptr;
        case TEXT_PREFIX:
            return n >= p.len && std::memcmp(text, p.text, p.len) == 0;
        case TEXT_SUFFIX:
            return n >= p.len && std::memcmp(text + n - p.len, p.text, p.len) == 0;
        case TEXT_WILDCARD:
            return wildcard(p.text, text);
        default:
            return true;
    }
}

/**
 * Whether a match's fields all agree with a row.
 *
 * @param m   the match
 * @param row the row
 * @return true if every masked field matches
 */
bool matches(const Match &m, const RowInfo &row) {
    if ((m.mask & MATCH_KIND) != 0 && m.kind != row.kind) {
        return false;
    }
    if ((m.mask & MATCH_TYPE_ID) != 0 && m.typeId != row.typeId) {
        return false;
    }
    if ((m.mask & MATCH_INDEX) != 0 && m.index != row.index) {
        return false;
    }
    if ((m.mask & MATCH_OP) != 0 && m.op != row.op) {
        return false;
    }
    if ((m.mask & MATCH_GROUP) != 0 && m.group != (row.typeId >> 16)) {
        return false;
    }
    if ((m.mask & MATCH_TILE) != 0 && (m.level != row.level || m.x != row.x || m.z != row.z)) {
        return false;
    }
    if ((m.mask & MATCH_OPTION) != 0 && !matchText(m.option, row.option)) {
        return false;
    }
    if ((m.mask & MATCH_TARGET) != 0 && !matchText(m.target, row.target)) {
        return false;
    }
    return true;
}

/**
 * Whether two rows are about the same thing: kind, type id, index and tile.
 *
 * @param a one row
 * @param b another
 * @return true if they share a subject
 */
bool sameSubject(const RowInfo &a, const RowInfo &b) {
    return a.kind == b.kind && a.typeId == b.typeId && a.index == b.index && a.level == b.level && a.x == b.x
           && a.z == b.z;
}

/**
 * Pushes a MENU_CLICK message into the ring. Game thread only.
 *
 * @param kind   CLICK_ROW or CLICK_RULE
 * @param id     the row id or rule id
 * @param parent the parent row id, or NO_ROW
 * @param x      the mouse x
 * @param y      the mouse y
 */
void reportClick(uint64_t kind, uint32_t id, uint32_t parent, int32_t x, int32_t y) {
    uint64_t regs[4] = {kind, id, parent,
                        (static_cast<uint64_t>(static_cast<uint32_t>(x)))
                        | (static_cast<uint64_t>(static_cast<uint32_t>(y)) << 32)};
    uint8_t body[48];
    int off = ring::writeHead(body, MENU_CLICK_EVENT_ID, regs);
    body[off++] = 0;
    ring::push(body, off);
}

/**
 * Pushes a MENU_EXPIRED message into the ring. Game thread only.
 *
 * @param id the rule that expired
 */
void reportExpired(uint32_t id) {
    uint64_t regs[4] = {id, 0, 0, 0};
    uint8_t body[48];
    int off = ring::writeHead(body, MENU_EXPIRED_EVENT_ID, regs);
    body[off++] = 0;
    ring::push(body, off);
}

/**
 * The minimenu behind the client.
 *
 * @return its address, or 0 while the client or the minimenu can't be read
 */
uint64_t minimenu() {
    uint64_t client = calls::clientPointer();
    return client == 0 ? 0 : readU64(client + g_layout.clientMinimenu, 0);
}

/**
 * Calls the client's node reserve under a guard.
 *
 * @param vector the node vector
 * @param count  the capacity wanted, in nodes
 * @return false if the call faulted
 */
bool callReserve(uint64_t vector, uint64_t count) {
    using Fn = void(__fastcall *)(uint64_t, uint64_t);
    Fn fn = reinterpret_cast<Fn>(process::base() + g_layout.nodeReserve);
    bool faulted = false;
    GUARD_BEGIN
        fn(vector, count);
    GUARD_END
    return !faulted;
}

/**
 * Calls the client's own node destroy on a children slot, under a guard.
 *
 * @param slot the address of the children slot
 * @return false if the call faulted
 */
bool callDestroy(uint64_t slot) {
    using Fn = void(__fastcall *)(uint64_t);
    Fn fn = reinterpret_cast<Fn>(g_destroyTrampoline);
    bool faulted = false;
    GUARD_BEGIN
        fn(slot);
    GUARD_END
    return !faulted;
}

/**
 * Calls the client's entry string assignment under a guard.
 *
 * @param dst the entry string
 * @param src the source string object
 * @return false if the call faulted
 */
bool callAssign(uint64_t dst, const void *src) {
    using Fn = void *(__fastcall *)(uint64_t, const void *);
    Fn fn = reinterpret_cast<Fn>(process::base() + g_layout.textAssign);
    bool faulted = false;
    GUARD_BEGIN
        fn(dst, src);
    GUARD_END
    return !faulted;
}

/**
 * Writes the rows vector's nodes and its end under a guard.
 *
 * @param rows  the rows vector object
 * @param begin its first node
 * @param data  the nodes to write
 * @param n     how many bytes
 * @return false if the write faulted
 */
bool writeNodes(uint64_t rows, uint64_t begin, const uint8_t *data, size_t n) {
    bool faulted = false;
    GUARD_BEGIN
        if (n > 0) {
            std::memcpy(reinterpret_cast<void *>(begin), data, n);
        }
        uint64_t newEnd = begin + n;
        std::memcpy(reinterpret_cast<void *>(rows + 8), &newEnd, 8);
    GUARD_END
    return !faulted;
}

/**
 * Builds an eastl string in its heap form over a buffer of this DLL's, as a source for the client's assignment: a
 * pointer, a size and a capacity with the heap flag set.
 *
 * @param object receives the 24-byte string object
 * @param text   the text
 * @param n      its length
 */
void sourceString(uint8_t *object, const char *text, uint64_t n) {
    std::memset(object, 0, 24);
    uint64_t pointer = reinterpret_cast<uint64_t>(text);
    std::memcpy(object, &pointer, 8);
    std::memcpy(object + 8, &n, 8);
    std::memcpy(object + 16, &n, 8);
    if (g_layout.stringFlagByte < 24) {
        object[g_layout.stringFlagByte] |= static_cast<uint8_t>(g_layout.stringFlagMask);
    }
}

/**
 * Writes a text into one of the client's entry strings through its own assignment, which keeps the text in the
 * entry's buffer or, when it doesn't fit, in the client's heap.
 *
 * @param dst  the entry string
 * @param text the text
 */
void assignText(uint64_t dst, const std::string &text) {
    uint8_t object[24];
    sourceString(object, text.data(), text.size());
    callAssign(dst, object);
}

/**
 * Works out how a Kraken entry string must be laid out from one of the client's: its buffer offset is where the
 * string's pool pointer points, its capacity runs to the entry field that follows it.
 *
 * @param entry    a valid entry of the client's
 * @param field    the string's offset in the entry
 * @param out      receives the template
 * @return false if the entry can't be read or doesn't look like a fixed-capacity string
 */
bool readTemplate(uint64_t entry, uint32_t field, StringTemplate &out) {
    const MenuLayout &l = g_layout;
    uint64_t string = entry + field;
    uint64_t pool = readU64(string + 24, 0);
    if (pool <= string) {
        return false;
    }
    uint64_t offset = pool - string;
    if (offset < MIN_BUFFER_OFFSET || offset > MAX_BUFFER_OFFSET) {
        return false;
    }
    uint32_t end = l.entrySize;
    const uint32_t fields[] = {l.option, l.target, l.typeId, l.subjectIndex, l.opIndex, l.level, l.x, l.z,
                               l.subjectType};
    for (uint32_t f : fields) {
        if (f > field && f < end) {
            end = f;
        }
    }
    uint64_t bufferEnd = field + offset;
    if (end <= bufferEnd + 1 || end - bufferEnd > MAX_TEXT_CAPACITY) {
        return false;
    }
    out.bufferOffset = static_cast<uint32_t>(offset);
    out.capacity = static_cast<uint32_t>(end - bufferEnd - 1);
    return safeRead(string + 32, out.allocator, ALLOCATOR_BYTES);
}

/**
 * Reads the string templates off the client's last-run copy, once.
 *
 * @param menu the minimenu
 * @return true once the templates are known
 */
bool ensureTemplates(uint64_t menu) {
    if (g_templateReady) {
        return true;
    }
    uint64_t clicked = menu + g_layout.clicked;
    if (!readTemplate(clicked, g_layout.option, g_optionTemplate)
            || !readTemplate(clicked, g_layout.target, g_targetTemplate)) {
        return false;
    }
    g_templateReady = true;
    return true;
}

/**
 * Lays a text out in a Kraken entry string: the heap form over the string's own buffer, as the client's constructor
 * does, with the text cut to the buffer.
 *
 * @param string the string object inside a Kraken entry
 * @param t      the string's template
 * @param text   the text
 */
void writeOwnString(uint8_t *string, const StringTemplate &t, const std::string &text) {
    uint8_t *buffer = string + t.bufferOffset;
    uint64_t n = text.size() > t.capacity ? t.capacity : text.size();
    std::memcpy(buffer, text.data(), n);
    buffer[n] = 0;
    uint64_t pointer = reinterpret_cast<uint64_t>(buffer);
    uint64_t capacity = t.capacity;
    std::memcpy(string, &pointer, 8);
    std::memcpy(string + 8, &n, 8);
    std::memcpy(string + 16, &capacity, 8);
    if (g_layout.stringFlagByte < 24) {
        string[g_layout.stringFlagByte] |= static_cast<uint8_t>(g_layout.stringFlagMask);
    }
    std::memcpy(string + 24, &pointer, 8);
    std::memcpy(string + 32, t.allocator, ALLOCATOR_BYTES);
}

/**
 * Fills one entry of the pool for a row of Kraken's own: no subject, MENU_ROW_OP as the op, the row id as the type
 * id and the parent's as the subject index.
 *
 * @param slot   the entry slot
 * @param id     the row id
 * @param parent the parent row id, or NO_ROW
 * @param option the option text
 * @param target the target text
 */
void writeEntry(int slot, uint32_t id, uint32_t parent, const std::string &option, const std::string &target) {
    const MenuLayout &l = g_layout;
    uint8_t *entry = g_entryPool + static_cast<size_t>(slot) * l.entrySize;
    std::memset(entry, 0, l.entrySize);
    writeOwnString(entry + l.option, g_optionTemplate, option);
    writeOwnString(entry + l.target, g_targetTemplate, target);
    int32_t typeId = static_cast<int32_t>(id);
    int32_t index = static_cast<int32_t>(parent);
    int32_t op = MENU_ROW_OP;
    int32_t none = -1;
    std::memcpy(entry + l.typeId, &typeId, 4);
    std::memcpy(entry + l.subjectIndex, &index, 4);
    std::memcpy(entry + l.opIndex, &op, 4);
    std::memcpy(entry + l.level, &none, 4);
    std::memcpy(entry + l.x, &none, 4);
    std::memcpy(entry + l.z, &none, 4);
    entry[l.subjectType] = 0;
}

/**
 * Writes a node showing an entry of Kraken's list.
 *
 * @param node     receives the node
 * @param slot     the entry slot
 * @param children the children vector object, or 0
 */
void ownNodeBytes(uint8_t *node, int slot, uint64_t children) {
    std::memset(node, 0, g_layout.nodeSize);
    setNodeField(node, g_layout.nodeList, reinterpret_cast<uint64_t>(g_list));
    setNodeField(node, g_layout.nodeIndex, static_cast<uint64_t>(slot));
    setNodeField(node, g_layout.nodeChildren, children);
}

/**
 * Lays out a row of Kraken's own and its sub rows in the pools.
 *
 * @param row the row
 * @return its index in g_krakenRows, or -1 if the pools are full
 */
int layOut(const AddedRow &row) {
    int children = row.children.size() > MAX_CHILDREN ? MAX_CHILDREN : static_cast<int>(row.children.size());
    if (g_krakenCount >= MAX_KRAKEN_ROWS || g_krakenCount + 1 + children > MAX_KRAKEN_ROWS) {
        return -1;
    }
    KrakenRow &k = g_krakenRows[g_krakenCount];
    k.id = row.id;
    k.entrySlot = g_krakenCount;
    k.childCount = children;
    k.firstChildSlot = children > 0 ? g_krakenCount + 1 : -1;
    writeEntry(k.entrySlot, row.id, NO_ROW, row.option, row.target);
    uint64_t *vec = g_childVectors[g_krakenCount];
    if (children > 0) {
        uint8_t *nodes = g_childNodePool + static_cast<size_t>(k.firstChildSlot) * g_layout.nodeSize;
        for (int i = 0; i < children; i++) {
            const AddedRow &child = row.children[static_cast<size_t>(i)];
            writeEntry(k.firstChildSlot + i, child.id, row.id, child.option, child.target);
            ownNodeBytes(nodes + static_cast<size_t>(i) * g_layout.nodeSize, k.firstChildSlot + i, 0);
        }
        vec[0] = reinterpret_cast<uint64_t>(nodes);
        vec[1] = vec[0] + static_cast<uint64_t>(children) * g_layout.nodeSize;
        vec[2] = vec[1];
    } else {
        vec[0] = vec[1] = vec[2] = 0;
    }
    int index = g_krakenCount;
    g_krakenCount += 1 + children;
    return index;
}

/**
 * Reads one row's entry into a RowInfo.
 *
 * @param info   the row, with its node already copied in
 * @param parent the top row a sub row hangs under, or -1
 * @return false if the node names no readable entry
 */
bool readRow(RowInfo &info, int parent) {
    const MenuLayout &l = g_layout;
    info.parent = parent;
    uint64_t list = nodeField(info.node, l.nodeList);
    uint64_t index = nodeField(info.node, l.nodeIndex);
    if (list == 0 || index > MAX_LIST_INDEX) {
        return false;
    }
    uint64_t entries = readU64(list + l.listEntries, 0);
    if (entries == 0) {
        return false;
    }
    info.entry = entries + index * l.entrySize;
    std::vector<uint8_t> block(l.entrySize);
    if (!safeRead(info.entry, block.data(), l.entrySize)) {
        return false;
    }
    info.kind = block[l.subjectType];
    std::memcpy(&info.typeId, block.data() + l.typeId, 4);
    std::memcpy(&info.index, block.data() + l.subjectIndex, 4);
    std::memcpy(&info.op, block.data() + l.opIndex, 4);
    std::memcpy(&info.level, block.data() + l.level, 4);
    std::memcpy(&info.x, block.data() + l.x, 4);
    std::memcpy(&info.z, block.data() + l.z, 4);
    uint8_t text[MAX_STRING_TEXT];
    normalise(text, readString(info.entry + l.option, text), info.option);
    normalise(text, readString(info.entry + l.target, text), info.target);
    info.swapped = false;
    info.hidden = false;
    info.deprioritized = false;
    info.reportRule = 0;
    return true;
}

/**
 * Reads the rows vector's nodes and their entries into g_rows, and, when any rule looks at sub rows, the children
 * of each row into g_subRows.
 *
 * @param begin   the first node
 * @param count   how many nodes
 * @param subRows whether to read the sub rows too
 */
void readRows(uint64_t begin, int count, bool subRows) {
    const MenuLayout &l = g_layout;
    g_rowCount = 0;
    g_subRowCount = 0;
    for (int i = 0; i < count; i++) {
        RowInfo &info = g_rows[g_rowCount];
        if (!safeRead(begin + static_cast<uint64_t>(i) * l.nodeSize, info.node, l.nodeSize)) {
            break;
        }
        if (ownNode(info.node)) {
            continue; // a row of Kraken's own left over; the client's rebuild never leaves one, but be safe
        }
        if (!readRow(info, -1)) {
            info.entry = 0;
            info.kind = 0xff;
            info.option[0] = 0;
            info.target[0] = 0;
        }
        int top = g_rowCount++;
        uint64_t children = nodeField(info.node, l.nodeChildren);
        if (!subRows || children == 0 || nodeField(info.node, l.nodeList) == 0) {
            continue;
        }
        uint64_t first = readU64(children, 0);
        uint64_t last = readU64(children + 8, 0);
        if (first == 0 || last <= first || (last - first) % l.nodeSize != 0) {
            continue;
        }
        uint64_t n = (last - first) / l.nodeSize;
        for (uint64_t c = 0; c < n && c < MAX_SUB_ROWS && g_subRowCount < MAX_ROWS * 2; c++) {
            RowInfo &sub = g_subRows[g_subRowCount];
            if (!safeRead(first + c * l.nodeSize, sub.node, l.nodeSize) || !readRow(sub, top)) {
                break;
            }
            g_subRowCount++;
        }
    }
}

/**
 * Whether a rule's shift condition holds now.
 *
 * @param rule  the rule
 * @param shift whether Shift is held
 * @return true if the rule applies in this shift state
 */
bool shiftAllows(const Rule &rule, bool shift) {
    if ((rule.flags & RULE_SHIFT_DOWN) != 0 && !shift) {
        return false;
    }
    return (rule.flags & RULE_SHIFT_UP) == 0 || !shift;
}

int findSlot(const std::vector<Slot> &order, int row) {
    for (size_t i = 0; i < order.size(); i++) {
        if (order[i].row == row) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

/**
 * Applies an EDIT rule to one top row that matched it: hides, deprioritizes, promotes, rewrites and marks it.
 *
 * @param rule  the rule
 * @param index the row's index in g_rows
 * @param order the slots so far
 */
void editRow(const Rule &rule, int index, std::vector<Slot> &order) {
    RowInfo &row = g_rows[index];
    if ((rule.actions & EDIT_HIDE) != 0) {
        row.hidden = true;
    }
    if ((rule.actions & EDIT_DEPRIORITIZE) != 0) {
        row.deprioritized = true;
    }
    if ((rule.actions & EDIT_SET_OPTION) != 0 && row.entry != 0) {
        assignText(row.entry + g_layout.option, rule.option);
    }
    if ((rule.actions & EDIT_SET_TARGET) != 0 && row.entry != 0) {
        assignText(row.entry + g_layout.target, rule.target);
    }
    if ((rule.actions & EDIT_REPORT) != 0) {
        row.reportRule = rule.id;
    }
    int at = findSlot(order, index);
    if (at < 0) {
        return;
    }
    if ((rule.actions & EDIT_PROMOTE) != 0 && at > 0) {
        std::swap(order[0], order[static_cast<size_t>(at)]);
    } else if ((rule.actions & EDIT_PROMOTE_SUBJECT) != 0) {
        for (size_t i = 0; i < static_cast<size_t>(at); i++) {
            if (order[i].row >= 0 && sameSubject(g_rows[order[i].row], row)) {
                std::swap(order[i], order[static_cast<size_t>(at)]);
                break;
            }
        }
    }
    if ((rule.flags & RULE_FINAL) != 0) {
        row.swapped = true;
    }
}

/**
 * Applies an EDIT rule to a sub row that matched it: rewrites its text, and copies it to the top when promoted.
 *
 * @param rule  the rule
 * @param index the sub row's index in g_subRows
 * @param order the slots so far
 */
void editSubRow(const Rule &rule, int index, std::vector<Slot> &order) {
    RowInfo &row = g_subRows[index];
    if ((rule.actions & EDIT_SET_OPTION) != 0 && row.entry != 0) {
        assignText(row.entry + g_layout.option, rule.option);
    }
    if ((rule.actions & EDIT_SET_TARGET) != 0 && row.entry != 0) {
        assignText(row.entry + g_layout.target, rule.target);
    }
    if ((rule.actions & (EDIT_PROMOTE | EDIT_PROMOTE_SUBJECT)) != 0) {
        for (const Slot &slot : order) {
            if (slot.sub == index) {
                return;
            }
        }
        Slot copy;
        copy.sub = index;
        order.insert(order.begin(), copy);
    }
}

/**
 * Applies a SWAP rule: each row matching the trigger swaps places with a row matching the swapped match, below it
 * when asked, with the same target or subject when asked, or with the top row when SWAP_TO_TOP is set.
 *
 * @param rule  the rule
 * @param order the slots so far
 */
void applySwap(const Rule &rule, std::vector<Slot> &order) {
    for (size_t t = 0; t < order.size(); t++) {
        int trigger = order[t].row;
        if (trigger < 0 || g_rows[trigger].swapped || !matches(rule.match, g_rows[trigger])) {
            continue;
        }
        for (size_t s = (rule.swapFlags & SWAP_BELOW) != 0 ? t + 1 : 0; s < order.size(); s++) {
            int found = order[s].row;
            if (found < 0 || found == trigger || g_rows[found].swapped || !matches(rule.swapped, g_rows[found])) {
                continue;
            }
            if ((rule.swapFlags & SWAP_SAME_TARGET) != 0
                    && std::strcmp(g_rows[found].target, g_rows[trigger].target) != 0) {
                continue;
            }
            if ((rule.swapFlags & SWAP_SAME_SUBJECT) != 0 && !sameSubject(g_rows[found], g_rows[trigger])) {
                continue;
            }
            if ((rule.swapFlags & SWAP_TO_TOP) != 0) {
                std::swap(order[0], order[s]);
            } else {
                std::swap(order[t], order[s]);
            }
            g_rows[found].swapped = true;
            g_rows[trigger].swapped = true;
            break;
        }
    }
}

/**
 * Applies an ADD rule: a row of Kraken's own next to each row matching the anchor, or at the top.
 *
 * @param rule  the rule
 * @param order the slots so far
 */
void applyAdd(const Rule &rule, std::vector<Slot> &order) {
    for (size_t i = 0; i < order.size(); i++) {
        int row = order[i].row;
        if (row < 0 || !matches(rule.match, g_rows[row])) {
            continue;
        }
        int kraken = layOut(rule.row);
        if (kraken < 0) {
            return;
        }
        Slot added;
        added.kraken = kraken;
        size_t at = rule.position == ADD_TOP ? 0 : rule.position == ADD_BELOW ? i + 1 : i;
        order.insert(order.begin() + static_cast<std::ptrdiff_t>(at), added);
        return; // one row per rule and rebuild, next to the highest match
    }
}

/**
 * Drains the commands the reader thread queued into the rule list. Takes the queue with a try-lock, so a reader
 * thread holding it costs one rebuild's delay rather than a stall.
 */
void drainCommands() {
    if (g_pendingCount.load(std::memory_order_acquire) == 0) {
        return;
    }
    std::vector<Command> work;
    if (!TryAcquireSRWLockExclusive(&g_pendingLock)) {
        return;
    }
    work.swap(g_pending);
    g_pendingCount.store(0, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_pendingLock);
    for (Command &command : work) {
        switch (command.op) {
            case MENU_REPLACE:
                g_rules.clear();
                for (Rule &rule : command.rules) {
                    if (g_rules.size() < MAX_RULES) {
                        g_rules.push_back(std::move(rule));
                    }
                }
                break;
            case MENU_UPSERT:
                for (Rule &rule : command.rules) {
                    bool replaced = false;
                    for (Rule &existing : g_rules) {
                        if (existing.id == rule.id) {
                            existing = std::move(rule);
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced && g_rules.size() < MAX_RULES) {
                        g_rules.push_back(std::move(rule));
                    }
                }
                break;
            case MENU_REMOVE:
                for (uint32_t id : command.ids) {
                    for (size_t i = 0; i < g_rules.size(); i++) {
                        if (g_rules[i].id == id) {
                            g_rules.erase(g_rules.begin() + static_cast<std::ptrdiff_t>(i));
                            break;
                        }
                    }
                }
                break;
            default:
                break;
        }
    }
}

/**
 * Drops the scoped rules that have gone unmatched for MENU_GRACE rebuilds, reporting each.
 */
void expireRules() {
    for (size_t i = 0; i < g_rules.size();) {
        Rule &rule = g_rules[i];
        if ((rule.flags & RULE_SCOPED) != 0 && rule.unmatched > MENU_GRACE) {
            reportExpired(rule.id);
            g_rules.erase(g_rules.begin() + static_cast<std::ptrdiff_t>(i));
        } else {
            i++;
        }
    }
}

/**
 * Edits the rows the client just built: drains the commands, reads the rows, applies every rule in order, then
 * writes the rows vector back in its new order with the hidden rows freed and Kraken's rows inserted.
 *
 * @param menu the minimenu
 */
void applyRules(uint64_t menu) {
    const MenuLayout &l = g_layout;
    drainCommands();
    g_krakenCount = 0;
    g_finalCount = 0;
    if (g_rules.empty()) {
        return;
    }
    uint64_t rows = menu + l.rows;
    uint64_t begin = readU64(rows, 0);
    uint64_t end = readU64(rows + 8, 0);
    uint64_t capacityEnd = readU64(rows + 16, 0);
    if (begin == 0 || end < begin || (end - begin) % l.nodeSize != 0 || (end - begin) / l.nodeSize > MAX_ROWS) {
        return;
    }
    int count = static_cast<int>((end - begin) / l.nodeSize);
    bool subRows = false;
    bool adds = false;
    for (const Rule &rule : g_rules) {
        subRows |= (rule.match.mask & MATCH_SUB_ROWS) != 0;
        adds |= rule.type == RULE_ADD;
    }
    readRows(begin, count, subRows);
    if (adds && !ensureTemplates(menu)) {
        adds = false;
    }
    bool shift = (GetAsyncKeyState(VK_SHIFT) & 0x8000) != 0;

    std::vector<Slot> order;
    order.reserve(static_cast<size_t>(g_rowCount) + 8);
    for (int i = 0; i < g_rowCount; i++) {
        Slot slot;
        slot.row = i;
        order.push_back(slot);
    }

    for (Rule &rule : g_rules) {
        bool matched = false;
        bool allowed = shiftAllows(rule, shift);
        if ((rule.match.mask & MATCH_SUB_ROWS) != 0) {
            for (int i = 0; i < g_subRowCount; i++) {
                if (matches(rule.match, g_subRows[i])) {
                    matched = true;
                    if (allowed && rule.type == RULE_EDIT) {
                        editSubRow(rule, i, order);
                    }
                }
            }
        } else if (rule.type == RULE_EDIT) {
            for (int i = 0; i < g_rowCount; i++) {
                if (matches(rule.match, g_rows[i])) {
                    matched = true;
                    if (allowed) {
                        editRow(rule, i, order);
                    }
                }
            }
        } else if (rule.type == RULE_SWAP) {
            for (int i = 0; i < g_rowCount && !matched; i++) {
                matched = matches(rule.match, g_rows[i]);
            }
            if (allowed) {
                applySwap(rule, order);
            }
        } else if (rule.type == RULE_ADD) {
            for (int i = 0; i < g_rowCount && !matched; i++) {
                matched = matches(rule.match, g_rows[i]);
            }
            if (allowed && adds) {
                applyAdd(rule, order);
            }
        }
        rule.unmatched = matched ? 0 : rule.unmatched + 1;
    }
    expireRules();

    // Hidden rows leave; deprioritized rows go to the bottom, above a Cancel row, in their order
    std::vector<Slot> kept;
    std::vector<Slot> low;
    for (const Slot &slot : order) {
        if (slot.row >= 0 && g_rows[slot.row].hidden) {
            uint64_t children = nodeField(g_rows[slot.row].node, l.nodeChildren);
            if (children != 0 && !ownVector(children)) {
                callDestroy(reinterpret_cast<uint64_t>(g_rows[slot.row].node + l.nodeChildren));
            }
        } else if (slot.row >= 0 && g_rows[slot.row].deprioritized) {
            low.push_back(slot);
        } else {
            kept.push_back(slot);
        }
    }
    if (!low.empty()) {
        size_t at = kept.size();
        if (at > 0 && kept[at - 1].row >= 0 && std::strcmp(g_rows[kept[at - 1].row].option, "cancel") == 0) {
            at--;
        }
        kept.insert(kept.begin() + static_cast<std::ptrdiff_t>(at), low.begin(), low.end());
    }

    int newCount = static_cast<int>(kept.size());
    if (newCount > MAX_ROWS) {
        newCount = MAX_ROWS;
    }
    uint64_t capacity = capacityEnd >= begin ? (capacityEnd - begin) / l.nodeSize : 0;
    if (static_cast<uint64_t>(newCount) > capacity) {
        if (!callReserve(rows, static_cast<uint64_t>(newCount))) {
            return;
        }
        begin = readU64(rows, 0);
        capacityEnd = readU64(rows + 16, 0);
        if (begin == 0 || capacityEnd < begin + static_cast<uint64_t>(newCount) * l.nodeSize) {
            return;
        }
    }
    std::vector<uint8_t> nodes(static_cast<size_t>(newCount) * l.nodeSize);
    for (int i = 0; i < newCount; i++) {
        uint8_t *node = nodes.data() + static_cast<size_t>(i) * l.nodeSize;
        const Slot &slot = kept[static_cast<size_t>(i)];
        if (slot.row >= 0) {
            std::memcpy(node, g_rows[slot.row].node, l.nodeSize);
            g_reportByRow[i] = g_rows[slot.row].reportRule;
        } else if (slot.sub >= 0) {
            std::memcpy(node, g_subRows[slot.sub].node, l.nodeSize);
            setNodeField(node, l.nodeChildren, 0);
            g_reportByRow[i] = 0;
        } else {
            const KrakenRow &k = g_krakenRows[slot.kraken];
            ownNodeBytes(node, k.entrySlot,
                         k.childCount > 0 ? reinterpret_cast<uint64_t>(g_childVectors[slot.kraken]) : 0);
            g_reportByRow[i] = 0;
        }
    }
    g_finalCount = writeNodes(rows, begin, nodes.data(), nodes.size()) ? newCount : 0;
}

/**
 * Takes the rows of Kraken's own out of the rows vector, before the client's rebuild clears it, so the client never
 * frees what it didn't allocate. Game thread only.
 *
 * @param menu the minimenu
 */
void removeOwnRows(uint64_t menu) {
    const MenuLayout &l = g_layout;
    uint64_t rows = menu + l.rows;
    uint64_t begin = readU64(rows, 0);
    uint64_t end = readU64(rows + 8, 0);
    if (begin == 0 || end < begin || (end - begin) % l.nodeSize != 0 || (end - begin) / l.nodeSize > MAX_ROWS) {
        return;
    }
    size_t count = static_cast<size_t>((end - begin) / l.nodeSize);
    std::vector<uint8_t> nodes(count * l.nodeSize);
    if (count == 0 || !safeRead(begin, nodes.data(), nodes.size())) {
        return;
    }
    std::vector<uint8_t> kept;
    kept.reserve(nodes.size());
    for (size_t i = 0; i < count; i++) {
        const uint8_t *node = nodes.data() + i * l.nodeSize;
        if (!ownNode(node)) {
            kept.insert(kept.end(), node, node + l.nodeSize);
        }
    }
    if (kept.size() != nodes.size()) {
        writeNodes(rows, begin, kept.data(), kept.size());
    }
}

/**
 * The view rebuild's detour: takes Kraken's rows out, lets the client clear and rebuild the rows, then applies the
 * rules to the fresh rows.
 *
 * @param view   the view
 * @param source the rebuild's source
 */
void __fastcall rebuildDetour(int64_t view, int64_t source) {
    uint64_t menu = minimenu();
    if (menu != 0) {
        removeOwnRows(menu);
    }
    using Fn = void(__fastcall *)(int64_t, int64_t);
    reinterpret_cast<Fn>(g_rebuildTrampoline)(view, source);
    if (menu != 0) {
        applyRules(menu);
    }
}

/**
 * The run's detour: a click on a row of Kraken's own (or a sub row of one) is reported and runs nothing; a click on
 * a client row runs as usual, then is reported if an EDIT_REPORT rule claimed the row.
 *
 * @param view   the view
 * @param path   the path vector: the row index, then child indices
 * @param mouseX the mouse x
 * @param mouseY the mouse y
 * @param flag   the fifth argument
 * @return whatever the original returns, or 1 for a row of Kraken's own
 */
int64_t __fastcall runDetour(int64_t view, int64_t path, int64_t mouseX, int64_t mouseY, int64_t flag) {
    using Fn = int64_t(__fastcall *)(int64_t, int64_t, int64_t, int64_t, int64_t);
    const MenuLayout &l = g_layout;
    uint64_t menu = minimenu();
    uint64_t pathBegin = readU64(static_cast<uint64_t>(path), 0);
    uint64_t pathEnd = readU64(static_cast<uint64_t>(path) + 8, 0);
    uint64_t depth = pathBegin != 0 && pathEnd > pathBegin ? (pathEnd - pathBegin) / 8 : 0;
    if (menu == 0 || depth == 0) {
        return reinterpret_cast<Fn>(g_runTrampoline)(view, path, mouseX, mouseY, flag);
    }
    uint64_t rowIndex = readU64(pathBegin, ~0ull);
    uint64_t rows = menu + l.rows;
    uint64_t begin = readU64(rows, 0);
    uint64_t end = readU64(rows + 8, 0);
    uint8_t node[MAX_NODE];
    if (begin == 0 || end < begin || rowIndex >= (end - begin) / l.nodeSize
            || !safeRead(begin + rowIndex * l.nodeSize, node, l.nodeSize)) {
        return reinterpret_cast<Fn>(g_runTrampoline)(view, path, mouseX, mouseY, flag);
    }
    if (ownNode(node)) {
        uint64_t slot = nodeField(node, l.nodeIndex);
        uint64_t children = nodeField(node, l.nodeChildren);
        if (depth > 1 && children != 0) {
            uint64_t child = readU64(pathBegin + 8, ~0ull);
            uint64_t first = readU64(children, 0);
            uint64_t last = readU64(children + 8, 0);
            uint8_t childNode[MAX_NODE];
            if (first != 0 && last > first && child < (last - first) / l.nodeSize
                    && safeRead(first + child * l.nodeSize, childNode, l.nodeSize) && ownNode(childNode)) {
                slot = nodeField(childNode, l.nodeIndex);
            }
        }
        if (slot < MAX_KRAKEN_ROWS) {
            const uint8_t *entry = g_entryPool + slot * l.entrySize;
            int32_t id;
            int32_t parent;
            std::memcpy(&id, entry + l.typeId, 4);
            std::memcpy(&parent, entry + l.subjectIndex, 4);
            reportClick(CLICK_ROW, static_cast<uint32_t>(id), static_cast<uint32_t>(parent),
                        static_cast<int32_t>(mouseX), static_cast<int32_t>(mouseY));
        }
        return 1;
    }
    int64_t ret = reinterpret_cast<Fn>(g_runTrampoline)(view, path, mouseX, mouseY, flag);
    if (rowIndex < static_cast<uint64_t>(g_finalCount) && g_reportByRow[rowIndex] != 0) {
        reportClick(CLICK_RULE, g_reportByRow[rowIndex], NO_ROW, static_cast<int32_t>(mouseX),
                    static_cast<int32_t>(mouseY));
    }
    return ret;
}

/**
 * The node destroy's detour: a children vector of Kraken's own is left alone, the client's are freed as usual.
 *
 * @param slot the address of a node's children slot
 */
void __fastcall destroyDetour(int64_t slot) {
    uint64_t vector = readU64(static_cast<uint64_t>(slot), 0);
    if (vector != 0 && ownVector(vector)) {
        return;
    }
    using Fn = void(__fastcall *)(int64_t);
    reinterpret_cast<Fn>(g_destroyTrampoline)(slot);
}

/**
 * Parses the handshake's menu block into a layout, in MenuLayout's field order.
 *
 * @param r   the block
 * @param out receives the layout
 * @return false if the block is too short
 */
bool parseLayout(FrameReader r, MenuLayout &out) {
    uint64_t *rvas[] = {&out.viewRebuild, &out.run, &out.nodeDestroy, &out.nodeReserve, &out.textAssign};
    uint32_t *offsets[] = {&out.clientMinimenu, &out.rows, &out.open, &out.clicked, &out.nodeList, &out.nodeIndex,
                           &out.nodeChildren, &out.nodeSize, &out.listEntries, &out.entrySize, &out.option,
                           &out.target, &out.typeId, &out.subjectIndex, &out.opIndex, &out.level, &out.x, &out.z,
                           &out.subjectType};
    uint8_t *kinds[] = {&out.kindTile, &out.kindNpc, &out.kindLoc, &out.kindObj, &out.kindPlayer,
                        &out.kindComponent};
    int32_t *ops[] = {&out.opExamine, &out.opTarget};
    uint32_t *strings[] = {&out.stringFlagByte, &out.stringFlagMask, &out.stringSsoCapacity};
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
    for (int32_t *op : ops) {
        if (!r.get(*op)) {
            return false;
        }
    }
    for (uint32_t *value : strings) {
        if (!r.get(*value)) {
            return false;
        }
    }
    return true;
}

/**
 * Whether a layout can be worked with: every function in the image, and sizes a pool can be made for.
 *
 * @param l the layout
 * @return true if the hooks may install
 */
bool usable(const MenuLayout &l) {
    const uint64_t functions[] = {l.viewRebuild, l.run, l.nodeDestroy, l.nodeReserve, l.textAssign};
    for (uint64_t fn : functions) {
        if (!process::inImage(fn)) {
            return false;
        }
    }
    return l.nodeSize >= 24 && l.nodeSize <= MAX_NODE && l.entrySize >= 64 && l.entrySize <= 4096
           && l.listEntries + 8 <= sizeof(g_list) && l.option + 40 < l.entrySize && l.target + 40 < l.entrySize
           && l.typeId + 4 <= l.entrySize && l.subjectIndex + 4 <= l.entrySize && l.opIndex + 4 <= l.entrySize
           && l.level + 4 <= l.entrySize && l.x + 4 <= l.entrySize && l.z + 4 <= l.entrySize
           && l.subjectType < l.entrySize && l.stringFlagByte < 24;
}

/**
 * Makes the entry and node pools and the list block, once.
 *
 * @return false if the pools couldn't be allocated
 */
bool makePools() {
    if (g_entryPool != nullptr) {
        return true;
    }
    g_entryPool = static_cast<uint8_t *>(std::calloc(MAX_KRAKEN_ROWS, g_layout.entrySize));
    g_childNodePool = static_cast<uint8_t *>(std::calloc(MAX_KRAKEN_ROWS, g_layout.nodeSize));
    if (g_entryPool == nullptr || g_childNodePool == nullptr) {
        return false;
    }
    std::memset(g_list, 0, sizeof(g_list));
    uint64_t entries = reinterpret_cast<uint64_t>(g_entryPool);
    std::memcpy(g_list + g_layout.listEntries, &entries, 8);
    std::memset(g_childVectors, 0, sizeof(g_childVectors));
    return true;
}

bool parsePattern(FrameReader &r, Pattern &out) {
    if (!r.get(out.mode) || !r.get(out.len) || out.len > MAX_PATTERN) {
        return false;
    }
    if (!r.bytes(reinterpret_cast<uint8_t *>(out.text), out.len)) {
        return false;
    }
    out.text[out.len] = 0;
    return true;
}

bool parseMatch(FrameReader &r, Match &out) {
    return r.get(out.mask) && r.get(out.kind) && r.get(out.typeId) && r.get(out.index) && r.get(out.op)
           && r.get(out.group) && r.get(out.level) && r.get(out.x) && r.get(out.z) && parsePattern(r, out.option)
           && parsePattern(r, out.target);
}

bool parseText(FrameReader &r, std::string &out) {
    uint16_t len = 0;
    if (!r.get(len) || len > MAX_RULE_TEXT || r.left < len) {
        return false;
    }
    out.assign(reinterpret_cast<const char *>(r.p), len);
    r.p += len;
    r.left -= len;
    return true;
}

bool parseRow(FrameReader &r, AddedRow &out, bool children) {
    if (!r.get(out.id) || !parseText(r, out.option) || !parseText(r, out.target)) {
        return false;
    }
    uint8_t count = 0;
    if (!r.get(count)) {
        return false;
    }
    if (!children && count != 0) {
        return false;
    }
    for (uint8_t i = 0; i < count && i < MAX_CHILDREN; i++) {
        AddedRow child;
        if (!parseRow(r, child, false)) {
            return false;
        }
        out.children.push_back(std::move(child));
    }
    return true;
}

bool parseRule(FrameReader &r, Rule &out) {
    if (!r.get(out.id) || !r.get(out.type) || !r.get(out.flags) || !parseMatch(r, out.match)) {
        return false;
    }
    switch (out.type) {
        case RULE_EDIT:
            return r.get(out.actions) && parseText(r, out.option) && parseText(r, out.target);
        case RULE_SWAP:
            return r.get(out.swapFlags) && parseMatch(r, out.swapped);
        case RULE_ADD:
            return r.get(out.position) && parseRow(r, out.row, true);
        default:
            return false;
    }
}

/**
 * Queues a command for the game thread.
 *
 * @param command the command
 */
void queue(Command &&command) {
    AcquireSRWLockExclusive(&g_pendingLock);
    g_pending.push_back(std::move(command));
    g_pendingCount.store(static_cast<uint32_t>(g_pending.size()), std::memory_order_release);
    ReleaseSRWLockExclusive(&g_pendingLock);
}

} // namespace

void install(FrameReader block) {
    MenuLayout layout;
    if (!parseLayout(block, layout)) {
        return;
    }
    if (!g_layoutSet) {
        if (!usable(layout)) {
            return;
        }
        g_layout = layout;
        if (!makePools()) {
            return;
        }
        g_layoutSet = true;
    }
    hooks::installOnce(g_layout.nodeDestroy, reinterpret_cast<void *>(&destroyDetour), &g_destroyTrampoline,
                       g_destroyHooked);
    if (!g_destroyHooked.load(std::memory_order_acquire)) {
        return; // without the guard on the destroy, a row of Kraken's own could be freed by the client
    }
    hooks::installOnce(g_layout.run, reinterpret_cast<void *>(&runDetour), &g_runTrampoline, g_runHooked);
    if (!g_runHooked.load(std::memory_order_acquire)) {
        return; // without the run hook, a click on a row of Kraken's own would run its empty entry
    }
    hooks::installOnce(g_layout.viewRebuild, reinterpret_cast<void *>(&rebuildDetour), &g_rebuildTrampoline,
                       g_rebuildHooked);
}

bool hooked() {
    return g_rebuildHooked.load(std::memory_order_acquire) && g_runHooked.load(std::memory_order_acquire)
           && g_destroyHooked.load(std::memory_order_acquire);
}

void command(FrameReader body) {
    if (!hooked()) {
        return;
    }
    Command command;
    uint16_t count = 0;
    if (!body.get(command.op) || !body.get(count)) {
        return;
    }
    if (command.op == MENU_REMOVE) {
        for (uint16_t i = 0; i < count; i++) {
            uint32_t id = 0;
            if (!body.get(id)) {
                return;
            }
            command.ids.push_back(id);
        }
    } else if (command.op == MENU_REPLACE || command.op == MENU_UPSERT) {
        for (uint16_t i = 0; i < count; i++) {
            Rule rule;
            if (!parseRule(body, rule)) {
                return; // a malformed frame changes nothing
            }
            command.rules.push_back(std::move(rule));
        }
    } else {
        return;
    }
    queue(std::move(command));
}

void clear() {
    if (!g_layoutSet) {
        return;
    }
    Command command;
    command.op = MENU_REPLACE;
    queue(std::move(command));
}

} // namespace kraken::menus
