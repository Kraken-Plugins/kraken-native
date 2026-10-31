// kraken_hook.dll - injected into osclient.exe. It turns the client's own event raises into push
// events for the Kraken client, runs the client functions the Kraken client asks for on the game
// thread, draws the Kraken client's overlay inside the game's own OpenGL frame, reports the scripts
// the client runs, edits the right-click menu by the rules the Kraken client sends, and remaps keys.
//
// On load it connects back to the Kraken client over a loopback socket and receives a handshake: a
// table of raise functions to hook, each with the event id it carries (fixed, or read from an
// argument register) and a capture plan saying which arguments to forward and how to read them,
// then the Client* slot, the per-frame pump function, the present function, the script runtime's
// layout, the minimenu's layout and the window procedure. Nothing here is hard-coded per client build: every RVA, event id, capture plan, function to
// call and argument comes from the mapping on the Java side. See README.md, docs/developer/native-hook.md
// and docs/developer/native-protocol.md.
//
// Why a socket and not a JNI/JNA callback: this DLL runs inside osclient.exe, which has no JVM, so a
// Java callback pointer means nothing in this address space. The socket is the bridge.
#include <windows.h>

#include "connection.h"
#include "core/hooks.h"
#include "core/process.h"
#include "core/ring.h"
#include "pump.h"
#include "raises.h"

namespace {

/**
 * Worker started off the loader lock: resolve the module base and size, set up MinHook and the
 * detour table, then run the writer thread for the life of the process.
 *
 * @return 1 on any setup failure; on success it never returns
 */
DWORD WINAPI init(LPVOID) {
    if (!kraken::connection::init() || !kraken::process::init()) {
        return 1;
    }
    kraken::raises::init();
    if (!kraken::ring::init() || !kraken::pump::init() || !kraken::hooks::init()) {
        return 1;
    }
    kraken::connection::run();
}

} // namespace

BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hinst);
        // A named mutex refuses a second injection into the same process.
        HANDLE mutex = CreateMutexW(nullptr, TRUE, L"Local\\KrakenHookInstance");
        if (mutex != nullptr && GetLastError() == ERROR_ALREADY_EXISTS) {
            return TRUE;
        }
        // Do the real work on our own thread; DllMain must not block or call most APIs under the loader lock.
        CreateThread(nullptr, 0, init, nullptr, 0, nullptr);
    }
    return TRUE;
}
