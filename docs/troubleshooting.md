# Troubleshooting

Most problems show up in two places: the status line on the **Kraken** tab, and the console at the bottom of the
window. When reporting a bug, press **Copy all** in the console and include the log.

## Building

### `No C++ toolchain found to build kraken_hook.dll`

The client build compiles the native hook from C++, and found no compiler. Either:

* install [Visual Studio Build Tools](https://visualstudio.microsoft.com/downloads/) (free) with the **Desktop
  development with C++** workload, then build again; or
* build without the hook, and do without what it adds (see [The native hook](client.md#the-native-hook)):

  ```powershell
  ./gradlew :client:run -x buildNativeHook
  ```

A 64-bit MinGW `g++` on `PATH` also works, but builds the hook without its crash guards, so use Visual Studio if you
can.

### `CMake 3.25 or later is needed to build kraken_hook.dll`

Add the **C++ CMake tools for Windows** component in the Visual Studio Installer, or install CMake from
[cmake.org](https://cmake.org/download/) and make sure it's on `PATH`.

### The first build fails offline

The first build downloads Gradle, a JDK 21 if you don't have one, the Java libraries, and MinHook for the native hook.
Run it once with a connection; later builds work offline.

### A `-D` option is ignored or Gradle complains about it

PowerShell splits `-Dname.with.dots=value` at the first dot. Quote it: `"-Dkraken.settings=D:\test.properties"`.

## Launching

### `Could not launch the client, see the console for details`

Kraken couldn't start `osclient.exe`. Usually the game isn't in the Jagex Launcher's default folder: pass its path, as
in `./gradlew :client:run --args="D:/Games/OSRS/osclient.exe"`. The console has the exact error.

### The game shows the old login screen

The game needs your Jagex account session to log in, which the Jagex Launcher normally supplies. See
[Logging in with a Jagex account](jagex-accounts.md).

### The game is drawn at the wrong size

The game sometimes keeps drawing at its old size after a resize. Kraken notices and resizes it again, up to three
times. If it's still wrong, resize the window or change **Game size** in Settings.

## Attaching

### `No mapping for client 241.4. Run the updater for it.`

The game has updated, and Kraken has no mapping for the new build, or has one made from a different `osclient.exe`.
The game still runs, but plugins and dev tools have nothing to read. Make a mapping with the updater: see
[Mapping a new game build](updater.md).

### `Could not attach to osclient.exe. See the console.`

Kraken found a mapping but couldn't read the game with it. The console has the reason. If it names a missing mapping
key, the mapping lacks a value Kraken needs: run the updater again and check its summary for what it left out.

### `Could not read the client's build or mapping. See the console.`

Kraken couldn't read `osclient.exe`'s version or the mapping file. Check the path you gave, and if you use
`kraken.mappings`, that the folder holds a readable `<version>.json`.

### The Plugins tab says `No plugins loaded`

Plugins load once Kraken has attached to the game. Wait for the status line to read **Attached**; if it doesn't, see
the messages above.

### A plugin is marked `failed`

Hover over **failed** for the reason, and look in the console for the full error. A plugin that fails to start is
switched off; switch it on again once the cause is fixed.

## The Native Hook

If overlays are drawn in a window over the game instead of inside it, highlights don't appear, or **Game FPS** shows
`–`, the native hook isn't connected. The console says why:

| Console message | What it means |
|---|---|
| `kraken_hook.dll was not found on the classpath` | The client was built with `-x buildNativeHook`. Build with a C++ toolchain to include it |
| `Native event hook: mapping 241.3 has no hookable events or drawing functions` | The mapping has none of the functions the hook needs. Re-run the updater |
| `Could not install the native event hook` | Loading it into the game failed. The stack trace below it says how. Security software that blocks loading code into other processes can cause this |
| `Native event hook disconnected; back on the timer poll until it reconnects.` | The connection dropped. It reconnects on its own |

Everything else keeps working without the hook.

## Settings

### Starting over

Close Kraken and delete `~/.kraken-native/settings.properties` to reset every setting, plugin switch and overlay
position. Dev tool choices are kept separately, in the Windows registry under
`HKEY_CURRENT_USER\Software\JavaSoft\Prefs\com\kraken\client\ui\devtools`.
