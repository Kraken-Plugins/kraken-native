// kraken_hook.dll - injected into osclient.exe to turn the client's ON_GAME_TICK dispatch into a
// push event for the Kraken client.
//
// On load it connects back to the Kraken client over a loopback socket and receives, in a short
// handshake, the event dispatcher's RVA and the ON_GAME_TICK event id - both read from the mapping
// for this exact build, so nothing here is hard-coded per client version. It then inline-hooks that
// dispatcher. The client calls the dispatcher with the event id in RCX; when the id is
// ON_GAME_TICK we send one byte back and the Kraken client fires its onGameTick listeners.
//
// Why a socket and not a JNI/JNA callback: this DLL runs inside osclient.exe, which has no JVM, so a
// Java callback pointer means nothing in this address space. The socket is the bridge. See README.md.

#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <cstdint>
#include "MinHook.h"

#pragma comment(lib, "ws2_32.lib")

// Must match GameTickHook.PORT on the Java side.
static const unsigned short KRAKEN_PORT = 42069;

static SOCKET g_sock = INVALID_SOCKET;
static int g_tickEventId = -1;

// The client's no-payload event dispatcher: osrs fires an event by id, passed in RCX. It takes no
// other arguments (it reads the client global itself), so a single-argument fastcall shape forwards
// it correctly.
using DispatchFn = std::int64_t(__fastcall *)(unsigned int);
static DispatchFn g_original = nullptr;

/**
 * Sends one byte to the Kraken client: one byte == one ON_GAME_TICK dispatch.
 */
static void notifyJava() {
    if (g_sock != INVALID_SOCKET) {
        char beat = 0x01;
        send(g_sock, &beat, 1, 0);
    }
}

/**
 * Runs in place of the event dispatcher. Signals Java only for ON_GAME_TICK, then calls the original
 * through MinHook's trampoline so every event still dispatches exactly as before.
 */
static std::int64_t __fastcall detour(unsigned int eventId) {
    if (static_cast<int>(eventId) == g_tickEventId) {
        notifyJava();
    }
    return g_original(eventId);
}

/**
 * Reads exactly len bytes from the socket. Returns false if the connection closed early.
 */
static bool recvExact(char *buf, int len) {
    int got = 0;
    while (got < len) {
        int n = recv(g_sock, buf + got, len - got, 0);
        if (n <= 0) {
            return false;
        }
        got += n;
    }
    return true;
}

/**
 * Opens the loopback socket back to the Kraken client. Java may still be binding when we load, so
 * this retries briefly rather than failing on the first refused connection.
 */
static bool connectToJava() {
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        return false;
    }
    g_sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (g_sock == INVALID_SOCKET) {
        return false;
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(KRAKEN_PORT);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
    for (int i = 0; i < 50; ++i) {
        if (connect(g_sock, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) == 0) {
            return true;
        }
        Sleep(100);
    }
    return false;
}

/**
 * Worker started off the loader lock: connect, read the handshake (dispatcher RVA + ON_GAME_TICK
 * id), resolve the target by module base + RVA, and arm the hook. Returns non-zero on any failure.
 */
static DWORD WINAPI init(LPVOID) {
    if (!connectToJava()) {
        return 1;
    }

    // Handshake: 8-byte little-endian dispatcher RVA, then 4-byte little-endian ON_GAME_TICK id.
    std::uint64_t rva = 0;
    std::int32_t eventId = 0;
    if (!recvExact(reinterpret_cast<char *>(&rva), sizeof(rva))) {
        return 1;
    }
    if (!recvExact(reinterpret_cast<char *>(&eventId), sizeof(eventId))) {
        return 1;
    }
    g_tickEventId = eventId;

    uintptr_t base = reinterpret_cast<uintptr_t>(GetModuleHandleW(L"osclient.exe"));
    if (base == 0) {
        return 1;
    }
    void *target = reinterpret_cast<void *>(base + rva);

    if (MH_Initialize() != MH_OK) {
        return 1;
    }
    if (MH_CreateHook(target, reinterpret_cast<void *>(&detour), reinterpret_cast<void **>(&g_original)) != MH_OK) {
        return 1;
    }
    if (MH_EnableHook(target) != MH_OK) {
        return 1;
    }
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hinst);
        // Do the real work on our own thread; DllMain must not block or call most APIs under the loader lock.
        CreateThread(nullptr, 0, init, nullptr, 0, nullptr);
    }
    return TRUE;
}
