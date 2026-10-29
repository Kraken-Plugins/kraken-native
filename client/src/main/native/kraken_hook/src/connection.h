// The connection to the Kraken client: a loopback socket the DLL opens back to it. The writer thread
// connects, reads the handshake and installs the hooks it names, then sends the ring's messages and
// the STATUS, FRAME and FPS reports; a reader thread per connection reads the commands. A dropped
// connection is re-established every second for the life of the process.
#pragma once

namespace kraken::connection {

/**
 * Starts Winsock. Call once, before run.
 *
 * @return false if Winsock failed to start
 */
bool init();

/**
 * Runs the writer thread on the calling thread: connect, read the handshake, start the connection's
 * reader thread, drain the ring to the socket, and reconnect for the life of the process if the
 * socket drops. Never returns.
 */
[[noreturn]] void run();

} // namespace kraken::connection
