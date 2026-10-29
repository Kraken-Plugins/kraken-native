#include "core/hooks.h"

#include <MinHook.h>

#include "core/process.h"

namespace kraken::hooks {

bool init() {
    return MH_Initialize() == MH_OK;
}

void installOnce(uint64_t rva, void *detour, void **trampoline, std::atomic<bool> &hooked) {
    if (!process::inImage(rva) || hooked.load(std::memory_order_acquire)) {
        return;
    }
    void *target = reinterpret_cast<void *>(process::base() + rva);
    if (MH_CreateHook(target, detour, trampoline) != MH_OK) {
        return;
    }
    if (MH_EnableHook(target) != MH_OK) {
        MH_RemoveHook(target);
        return;
    }
    hooked.store(true, std::memory_order_release);
}

} // namespace kraken::hooks
