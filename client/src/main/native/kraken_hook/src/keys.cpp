#include "keys.h"

#include <windows.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <utility>
#include <vector>

#include "core/hooks.h"
#include "core/process.h"
#include "core/ring.h"
#include "protocol.h"

namespace kraken::keys {
namespace {

constexpr int MAX_RULES_PER_MODE = 256;
constexpr uint16_t VK_COUNT = 256;

/**
 * One key rule of a mode: the key it waits for, the modifiers it needs, the key it becomes and the mode it moves to.
 */
struct KeyRule {
    uint16_t vk = 0;
    uint8_t modifiers = 0;
    uint8_t modifierMask = 0;
    uint16_t to = 0;
    uint8_t next = KEY_STAY;
};

/**
 * One mode: its flags and rules.
 */
struct KeyMode {
    uint8_t flags = 0;
    std::vector<KeyRule> rules;
};

/**
 * The table the last KEYS_TABLE command set. Replaced whole, so the window thread reads one table or the next.
 */
struct Table {
    std::vector<KeyMode> modes;
};

uint64_t g_wndProc = 0;                   // written once, before the hook installs
bool g_layoutSet = false;                 // handshake thread only
std::atomic<bool> g_hooked{false};
void *g_trampoline = nullptr;

SRWLOCK g_tableLock = SRWLOCK_INIT;
std::shared_ptr<const Table> g_table;     // guarded by g_tableLock
std::atomic<uint8_t> g_mode{0};
std::atomic<bool> g_report{false};

// Window thread only: the key each held key became, and whether the next WM_CHAR belongs to a remapped key.
uint16_t g_remapped[VK_COUNT];
bool g_dropNextChar = false;

/**
 * The table now.
 *
 * @return the table, or null while none is set
 */
std::shared_ptr<const Table> table() {
    AcquireSRWLockShared(&g_tableLock);
    std::shared_ptr<const Table> current = g_table;
    ReleaseSRWLockShared(&g_tableLock);
    return current;
}

/**
 * Replaces the table.
 *
 * @param next the new table, or null for none
 */
void setTable(std::shared_ptr<const Table> next) {
    AcquireSRWLockExclusive(&g_tableLock);
    g_table = std::move(next);
    ReleaseSRWLockExclusive(&g_tableLock);
}

/**
 * The KEY_SHIFT, KEY_CTRL and KEY_ALT bits of the modifiers held now.
 *
 * @return the bits
 */
uint8_t modifiersHeld() {
    uint8_t bits = 0;
    if ((GetKeyState(VK_SHIFT) & 0x8000) != 0) {
        bits |= KEY_SHIFT;
    }
    if ((GetKeyState(VK_CONTROL) & 0x8000) != 0) {
        bits |= KEY_CTRL;
    }
    if ((GetKeyState(VK_MENU) & 0x8000) != 0) {
        bits |= KEY_ALT;
    }
    return bits;
}

/**
 * Pushes a KEY message into the ring, if the Kraken client asked for them.
 *
 * @param message the window message
 * @param key     the key or character it carried
 * @param action  KEY_PASSED, KEY_REMAPPED or KEY_BLOCKED
 * @param to      the key it became
 * @param before  the mode before the message
 * @param after   the mode after it
 */
void report(uint32_t message, uint64_t key, uint64_t action, uint64_t to, uint8_t before, uint8_t after) {
    if (!g_report.load(std::memory_order_relaxed)) {
        return;
    }
    uint64_t regs[4] = {message, key, action << 16 | (to & 0xffff),
                        static_cast<uint64_t>(before) | static_cast<uint64_t>(after) << 8};
    uint8_t body[48];
    int off = ring::writeHead(body, KEY_EVENT_ID, regs);
    body[off++] = 0;
    ring::push(body, off);
}

/**
 * The rule of a mode that a key going down with these modifiers fires.
 *
 * @param mode      the mode
 * @param vk        the virtual key
 * @param modifiers the modifiers held
 * @return the rule, or null for none
 */
const KeyRule *find(const KeyMode &mode, uint16_t vk, uint8_t modifiers) {
    for (const KeyRule &rule : mode.rules) {
        if (rule.vk == vk && (modifiers & rule.modifierMask) == rule.modifiers) {
            return &rule;
        }
    }
    return nullptr;
}

/**
 * Handles a key going down: a rule of the mode in force may drop it, turn it into another key (dropping the
 * character it would type) or move to another mode. A key already remapped while held keeps its mapping as it
 * repeats.
 *
 * @param wParam the message's key, updated in place
 * @return false to drop the message
 */
bool keyDown(WPARAM &wParam) {
    uint16_t vk = static_cast<uint16_t>(wParam & 0xff);
    uint8_t before = g_mode.load(std::memory_order_relaxed);
    if (g_remapped[vk] != 0) {
        uint16_t to = g_remapped[vk];
        g_dropNextChar = true;
        if (to == KEY_TO_BLOCK) {
            report(WM_KEYDOWN, vk, KEY_BLOCKED, 0, before, before);
            return false;
        }
        wParam = to;
        report(WM_KEYDOWN, vk, KEY_REMAPPED, to, before, before);
        return true;
    }
    std::shared_ptr<const Table> current = table();
    if (!current || before >= current->modes.size()) {
        return true;
    }
    const KeyRule *rule = find(current->modes[before], vk, modifiersHeld());
    if (rule == nullptr) {
        return true;
    }
    uint8_t after = before;
    if (rule->next != KEY_STAY && rule->next < current->modes.size()) {
        after = rule->next;
        g_mode.store(after, std::memory_order_relaxed);
    }
    if (rule->to == KEY_TO_BLOCK) {
        g_remapped[vk] = KEY_TO_BLOCK;
        g_dropNextChar = true;
        report(WM_KEYDOWN, vk, KEY_BLOCKED, 0, before, after);
        return false;
    }
    if (rule->to != vk && rule->to < VK_COUNT) {
        g_remapped[vk] = rule->to;
        g_dropNextChar = true;
        wParam = rule->to;
        report(WM_KEYDOWN, vk, KEY_REMAPPED, rule->to, before, after);
        return true;
    }
    report(WM_KEYDOWN, vk, KEY_PASSED, vk, before, after);
    return true;
}

/**
 * Handles a key going up: a key that went down remapped goes up as the key it became, or not at all if it was
 * dropped.
 *
 * @param wParam the message's key, updated in place
 * @return false to drop the message
 */
bool keyUp(WPARAM &wParam) {
    uint16_t vk = static_cast<uint16_t>(wParam & 0xff);
    uint16_t to = g_remapped[vk];
    if (to == 0) {
        return true;
    }
    g_remapped[vk] = 0;
    uint8_t mode = g_mode.load(std::memory_order_relaxed);
    if (to == KEY_TO_BLOCK) {
        report(WM_KEYUP, vk, KEY_BLOCKED, 0, mode, mode);
        return false;
    }
    wParam = to;
    report(WM_KEYUP, vk, KEY_REMAPPED, to, mode, mode);
    return true;
}

/**
 * Handles a character typed: the character of a key that was remapped or dropped goes nowhere, and so does
 * every printable character while the mode blocks typing.
 *
 * @param wParam the character
 * @return false to drop the message
 */
bool character(WPARAM wParam) {
    uint8_t mode = g_mode.load(std::memory_order_relaxed);
    if (g_dropNextChar) {
        g_dropNextChar = false;
        report(WM_CHAR, wParam, KEY_BLOCKED, 0, mode, mode);
        return false;
    }
    std::shared_ptr<const Table> current = table();
    if (current && mode < current->modes.size() && (current->modes[mode].flags & KEY_MODE_BLOCK_TYPING) != 0
            && wParam >= 0x20) {
        report(WM_CHAR, wParam, KEY_BLOCKED, 0, mode, mode);
        return false;
    }
    return true;
}

/**
 * The window procedure's detour: rewrites or drops the key messages by the mode in force and passes everything
 * else through.
 *
 * @param hwnd    the window
 * @param message the message
 * @param wParam  its first parameter
 * @param lParam  its second parameter
 * @return whatever the original returns, or 0 for a message dropped
 */
LRESULT CALLBACK wndProcDetour(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    bool pass = true;
    switch (message) {
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN:
            pass = keyDown(wParam);
            break;
        case WM_KEYUP:
        case WM_SYSKEYUP:
            pass = keyUp(wParam);
            break;
        case WM_CHAR:
            pass = character(wParam);
            break;
        default:
            break;
    }
    if (!pass) {
        return 0;
    }
    using Fn = LRESULT(CALLBACK *)(HWND, UINT, WPARAM, LPARAM);
    return reinterpret_cast<Fn>(g_trampoline)(hwnd, message, wParam, lParam);
}

/**
 * Parses a KEYS_TABLE body into a table.
 *
 * @param r   the body after the operation byte
 * @param out receives the table
 * @return false if the body is malformed
 */
bool parseTable(FrameReader &r, Table &out) {
    uint8_t modes = 0;
    if (!r.get(modes) || modes > MAX_KEY_MODES) {
        return false;
    }
    for (uint8_t m = 0; m < modes; m++) {
        KeyMode mode;
        uint16_t count = 0;
        if (!r.get(mode.flags) || !r.get(count) || count > MAX_RULES_PER_MODE) {
            return false;
        }
        for (uint16_t i = 0; i < count; i++) {
            KeyRule rule;
            if (!r.get(rule.vk) || !r.get(rule.modifiers) || !r.get(rule.modifierMask) || !r.get(rule.to)
                    || !r.get(rule.next)) {
                return false;
            }
            mode.rules.push_back(rule);
        }
        out.modes.push_back(std::move(mode));
    }
    return true;
}

} // namespace

void install(FrameReader block) {
    uint64_t wndProc = 0;
    if (!block.get(wndProc)) {
        return;
    }
    if (!g_layoutSet) {
        if (!process::inImage(wndProc)) {
            return;
        }
        g_wndProc = wndProc;
        g_layoutSet = true;
    }
    hooks::installOnce(g_wndProc, reinterpret_cast<void *>(&wndProcDetour), &g_trampoline, g_hooked);
}

bool hooked() {
    return g_hooked.load(std::memory_order_acquire);
}

void command(FrameReader body) {
    uint8_t op = 0;
    if (!body.get(op) || !hooked()) {
        return;
    }
    switch (op) {
        case KEYS_TABLE: {
            auto parsed = std::make_shared<Table>();
            if (parseTable(body, *parsed)) {
                setTable(parsed->modes.empty() ? nullptr : std::shared_ptr<const Table>(parsed));
            }
            break;
        }
        case KEYS_MODE: {
            uint8_t mode = 0;
            if (body.get(mode) && mode < MAX_KEY_MODES) {
                g_mode.store(mode, std::memory_order_relaxed);
            }
            break;
        }
        case KEYS_REPORT: {
            uint8_t on = 0;
            if (body.get(on)) {
                g_report.store(on != 0, std::memory_order_relaxed);
            }
            break;
        }
        default:
            break;
    }
}

void clear() {
    setTable(nullptr);
    g_mode.store(0, std::memory_order_relaxed);
    g_report.store(false, std::memory_order_relaxed);
}

} // namespace kraken::keys
