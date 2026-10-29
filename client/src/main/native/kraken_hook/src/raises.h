// The event raise hooks. The handshake names the client's raise functions; each gets a generic detour
// that captures the event id and the planned argument values, queues a message for the writer thread
// and forwards to the original unchanged.
#pragma once

#include <cstdint>

#include "protocol.h"

namespace kraken::raises {

/**
 * One capture-plan entry: read argument register {@code arg} (0=RCX..3=R9) with op {@code op}.
 */
struct PlanEntry {
    uint8_t arg;
    uint8_t op;
};

/**
 * One hooked raise function: where it is, how to find the event id it carries, how many argument
 * registers to forward and the plan for reading payload out of them.
 */
struct Slot {
    void *target = nullptr;     // module base + rva
    void *trampoline = nullptr; // the original, through MinHook
    uint64_t rva = 0;
    int32_t fixedId = -1;       // the event id this raiser always carries, or -1 to read it from idArg
    uint8_t idArg = 0;          // register holding the id when fixedId < 0
    uint8_t args = 0;           // registers to forward (0..4)
    uint8_t planLen = 0;
    PlanEntry plan[MAX_PLAN];
};

/**
 * Fills the detour table. Call once, before any slot is installed.
 */
void init();

/**
 * Installs one slot's hook unless its rva is already hooked or the table is full.
 *
 * @param slot the slot to install (its target and trampoline are filled in here)
 */
void install(Slot slot);

/**
 * How many slots are hooked.
 *
 * @return the number of live raise hooks
 */
int installedCount();

} // namespace kraken::raises
