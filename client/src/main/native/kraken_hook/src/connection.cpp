#include "connection.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

#include "calls.h"
#include "components.h"
#include "core/frame_reader.h"
#include "core/process.h"
#include "core/ring.h"
#include "keys.h"
#include "menus.h"
#include "overlay/overlay.h"
#include "overlay/surface.h"
#include "protocol.h"
#include "pump.h"
#include "raises.h"
#include "scripts.h"
#include "visibility.h"

namespace kraken::connection {
namespace {

SOCKET g_sock = INVALID_SOCKET;
std::atomic<bool> g_connBroken{false}; // set by the reader thread when the socket closes

/**
 * Reads exactly len bytes from the socket.
 *
 * @param buf receives the bytes
 * @param len how many
 * @return false if the connection closed early
 */
bool recvExact(char *buf, int len) {
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
 * Sends exactly len bytes to the socket.
 *
 * @param buf the bytes
 * @param len how many
 * @return false on any socket error
 */
bool sendExact(const char *buf, int len) {
    int sent = 0;
    while (sent < len) {
        int n = send(g_sock, buf + sent, len - sent, 0);
        if (n <= 0) {
            return false;
        }
        sent += n;
    }
    return true;
}

/**
 * The per-connection reader thread: reads command frames until the socket closes, then tells the
 * writer thread the connection is gone.
 *
 * @return 0
 */
DWORD WINAPI readerThread(LPVOID) {
    std::vector<uint8_t> frame;
    for (;;) {
        uint32_t len = 0;
        if (!recvExact(reinterpret_cast<char *>(&len), 4) || len == 0 || len > MAX_FRAME) {
            break;
        }
        frame.resize(len);
        if (!recvExact(reinterpret_cast<char *>(frame.data()), static_cast<int>(len))) {
            break;
        }
        FrameReader r{frame.data() + 1, len - 1u};
        switch (frame[0]) {
            case CMD_CALLS:
                calls::queue(r);
                break;
            case CMD_SURFACE:
                overlay::openSurface(r);
                break;
            case CMD_FRAME_LIMIT:
                pump::setFrameLimit(r);
                break;
            case CMD_SCRIPTS:
                scripts::setCapture(r);
                break;
            case CMD_MENU:
                menus::command(r);
                break;
            case CMD_KEYS:
                keys::command(r);
                break;
            case CMD_VISIBILITY:
                visibility::command(r);
                break;
            case CMD_COMPONENTS:
                components::queue(r);
                break;
            default:
                break; // an unknown command is skipped, so a newer client can still talk to this DLL
        }
    }
    g_connBroken.store(true, std::memory_order_release);
    ring::wakeWriter();
    return 0;
}

/**
 * Sends a reserved message straight to the socket. Writer thread only, like every socket write.
 *
 * @param id   the reserved id
 * @param regs the four register values
 * @return false if the send failed
 */
bool sendDirect(int32_t id, const uint64_t regs[4]) {
    uint8_t body[48];
    int off = ring::writeHead(body, id, regs);
    body[off++] = 0;
    uint32_t len = static_cast<uint32_t>(off);
    return sendExact(reinterpret_cast<const char *>(&len), 4)
           && sendExact(reinterpret_cast<const char *>(body), off);
}

/**
 * Sends the status message: how many of the requested hooks installed, which features are live and
 * the protocol version.
 *
 * @param requested the number of slots the handshake asked for
 */
void sendStatus(uint16_t requested) {
    bool pumpHooked = pump::hooked();
    bool presentHooked = overlay::hooked();
    uint64_t features = (pumpHooked ? FEATURE_PUMP : 0) | (presentHooked ? FEATURE_PRESENT : 0)
                        | (pumpHooked || presentHooked ? FEATURE_FRAME_RATE : 0)
                        | (scripts::hooked() ? FEATURE_SCRIPTS : 0) | (menus::hooked() ? FEATURE_MENU : 0)
                        | (keys::hooked() ? FEATURE_KEYS : 0) | (visibility::live() ? FEATURE_VISIBILITY : 0)
                        | (components::live() ? FEATURE_COMPONENTS : 0);
    uint64_t regs[4] = {static_cast<uint64_t>(raises::installedCount()), requested, features, PROTOCOL_VERSION};
    sendDirect(STATUS_EVENT_ID, regs);
}

/**
 * Reads one length-prefixed block of the handshake: a u16 length, then the block, which goes to a module's
 * install when it isn't empty.
 *
 * @param install the module's install
 * @return false if the connection closed mid-block
 */
bool readBlock(void (*install)(FrameReader)) {
    uint16_t length = 0;
    if (!recvExact(reinterpret_cast<char *>(&length), 2)) {
        return false;
    }
    std::vector<uint8_t> block(length);
    if (length > 0 && !recvExact(reinterpret_cast<char *>(block.data()), length)) {
        return false;
    }
    if (length > 0) {
        install(FrameReader{block.data(), block.size()});
    }
    return true;
}

/**
 * Reads the handshake and installs any hooks not installed yet. The handshake is: magic "KRK7", a
 * u16 slot count, each slot's rva, id configuration, argument count and capture plan, then the
 * Client* slot, the pump function and the present function, then five length-prefixed blocks: the
 * script block, the menu block, the key block, the visibility block and the component block (each
 * empty for no such feature). A slot whose rva is already hooked is left alone, so a re-handshake
 * after a reconnect only adds new ones; the pump, present, script, menu and key hooks install once,
 * and the visibility and component layouts are taken once.
 *
 * @return true if the handshake parsed; false if the connection closed mid-handshake
 */
bool readHandshake() {
    char magic[4];
    if (!recvExact(magic, 4) || std::memcmp(magic, "KRK7", 4) != 0) {
        return false;
    }
    uint16_t count = 0;
    if (!recvExact(reinterpret_cast<char *>(&count), 2)) {
        return false;
    }
    for (uint16_t i = 0; i < count; i++) {
        raises::Slot slot;
        if (!recvExact(reinterpret_cast<char *>(&slot.rva), 8)
                || !recvExact(reinterpret_cast<char *>(&slot.fixedId), 4)
                || !recvExact(reinterpret_cast<char *>(&slot.idArg), 1)
                || !recvExact(reinterpret_cast<char *>(&slot.args), 1)
                || !recvExact(reinterpret_cast<char *>(&slot.planLen), 1)) {
            return false;
        }
        if (slot.planLen > MAX_PLAN) {
            return false;
        }
        for (uint8_t p = 0; p < slot.planLen; p++) {
            if (!recvExact(reinterpret_cast<char *>(&slot.plan[p]), 2)) {
                return false;
            }
        }
        raises::install(slot);
    }
    uint64_t tail[3] = {0, 0, 0};
    if (!recvExact(reinterpret_cast<char *>(tail), sizeof(tail))) {
        return false;
    }
    calls::setClientGlobal(tail[0]);
    pump::install(tail[1]);
    overlay::install(tail[2]);
    if (!readBlock(&scripts::install) || !readBlock(&menus::install) || !readBlock(&keys::install)
            || !readBlock(&visibility::install) || !readBlock(&components::install)) {
        return false;
    }
    sendStatus(count);
    return true;
}

/**
 * The FRAME values last sent on this connection.
 */
struct FrameState {
    uint32_t width = 0xffffffffu;
    uint32_t height = 0xffffffffu;
    uint32_t version = 0xffffffffu;
    uint32_t generation = 0xffffffffu;
};

/**
 * Sends FRAME if the present thread's framebuffer, GL version or drawn surface changed since the
 * last one sent. Nothing is sent before the first present has run.
 *
 * @param last the values last sent, updated when a FRAME goes out
 * @return false if the send failed
 */
bool sendFrameIfChanged(FrameState &last) {
    overlay::FrameInfo info = overlay::frameInfo();
    FrameState now{info.width, info.height, info.glVersion, info.generation};
    if (now.version == 0 && now.width == 0 && now.height == 0) {
        return true;
    }
    if (now.width == last.width && now.height == last.height && now.version == last.version
            && now.generation == last.generation) {
        return true;
    }
    last = now;
    uint64_t regs[4] = {now.width, now.height, now.version, now.generation};
    return sendDirect(FRAME_EVENT_ID, regs);
}

/**
 * The frame count and time FPS last measured from on this connection.
 */
struct FpsState {
    bool started = false;
    uint64_t frames = 0;
    int64_t ticks = 0;
};

/**
 * Sends FPS once a second or more has passed since the last: the frames counted since and how long
 * that was. Counts presents while the present hook is live, else frame steps; sends nothing while
 * neither is hooked.
 *
 * @param last the count and time last measured from, updated when an FPS goes out
 * @return false if the send failed
 */
bool sendFpsIfDue(FpsState &last) {
    bool present = overlay::hooked();
    if (!present && !pump::hooked()) {
        return true;
    }
    uint64_t frames = present ? overlay::presents() : pump::frameSteps();
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    if (!last.started) {
        last.started = true;
        last.frames = frames;
        last.ticks = now.QuadPart;
        return true;
    }
    int64_t frequency = process::counterFrequency();
    int64_t elapsed = now.QuadPart - last.ticks;
    if (elapsed < frequency) {
        return true;
    }
    uint64_t regs[4] = {frames - last.frames, static_cast<uint64_t>(elapsed * 1000000 / frequency),
                        pump::frameLimit(), present ? 1u : 0u};
    last.frames = frames;
    last.ticks = now.QuadPart;
    return sendDirect(FPS_EVENT_ID, regs);
}

/**
 * Drains the ring to the socket, and sends FRAME when the present thread's values change and FPS once
 * a second, until a send fails or the reader thread sees the socket close.
 *
 * @return false when the connection should be re-established
 */
bool drainRing() {
    FrameState last;
    FpsState fps;
    for (;;) {
        if (!ring::sendQueued(sendExact)) {
            return false;
        }
        if (!sendFrameIfChanged(last) || !sendFpsIfDue(fps)) {
            return false;
        }
        if (g_connBroken.load(std::memory_order_acquire)) {
            return false;
        }
        ring::waitForData();
    }
}

/**
 * Opens the loopback socket back to the Kraken client. Java may still be binding when we load, so
 * this retries briefly rather than failing on the first refused connection.
 *
 * @param attempts how many times to retry, 100 ms apart
 * @return true once connected
 */
bool connectToJava(int attempts) {
    g_sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (g_sock == INVALID_SOCKET) {
        return false;
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(KRAKEN_PORT);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
    for (int i = 0; i < attempts; i++) {
        if (connect(g_sock, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) == 0) {
            return true;
        }
        Sleep(100);
    }
    closesocket(g_sock);
    g_sock = INVALID_SOCKET;
    return false;
}

} // namespace

bool init() {
    WSADATA wsa;
    return WSAStartup(MAKEWORD(2, 2), &wsa) == 0;
}

void run() {
    // Hooks stay installed across reconnects; a dropped socket costs the client latency, not events,
    // because the Java side keeps polling as a fallback. A dropped socket also drops the queued calls
    // and component ops, stops the overlay, since nothing would keep it current, stops the script
    // events, drops the menu rules and key table, shows again what the visibility rules hid and lifts
    // the frame cap.
    bool first = true;
    for (;;) {
        if (!connectToJava(first ? 50 : 10)) {
            Sleep(1000);
            continue;
        }
        first = false;
        g_connBroken.store(false, std::memory_order_release);
        if (readHandshake()) {
            HANDLE reader = CreateThread(nullptr, 0, readerThread, nullptr, 0, nullptr);
            drainRing();
            shutdown(g_sock, SD_BOTH);
            if (reader != nullptr) {
                WaitForSingleObject(reader, INFINITE);
                CloseHandle(reader);
            }
        }
        if (g_sock != INVALID_SOCKET) {
            closesocket(g_sock);
            g_sock = INVALID_SOCKET;
        }
        calls::clear();
        components::clear();
        visibility::clear();
        overlay::stopSurface();
        scripts::stopCapture();
        menus::clear();
        keys::clear();
        pump::liftFrameLimit();
        Sleep(1000); // the writer reconnects every second, then Java re-sends the handshake
    }
}

} // namespace kraken::connection
