# Using the Client

The Kraken client starts the official Old School RuneScape client (`osclient.exe`), embeds its window, and adds a
sidebar with plugins, dev tools and settings around it. This page covers launching it and everything you see once it's
running.

## Launching

From the repository root:

```powershell
./gradlew :client:run
```

Kraken starts `osclient.exe` from the Jagex Launcher's default install folder,
`C:\Program Files (x86)\Jagex Launcher\Games\Old School RuneScape\Client\`. Don't start the game from the launcher
first: Kraken starts its own copy, and closing Kraken closes it.

To log in with a Jagex account, set your session variables before launching. See
[Logging in with a Jagex account](jagex-accounts.md).

### Command-Line Options

Pass options through Gradle's `--args`:

```powershell
./gradlew :client:run --args="D:/Games/OSRS/osclient.exe --developer-mode"
```

| Option | What it does |
|---|---|
| `<path to osclient.exe>` | Starts this copy of the game instead of the default one. Wrap a path with spaces in single quotes inside the double ones: `--args="'D:/My Games/osclient.exe'"` |
| `--plugin <class>` | Also loads this plugin class, which must be on the classpath. Repeat it for several. See [Writing a plugin](writing-plugins.md) |
| `--developer-mode` | Also loads plugins marked as developer plugins. None of the built-in plugins are |

Options Kraken doesn't recognise are reported in the console.

### System Properties

| Property | What it does |
|---|---|
| `kraken.settings=<file>` | Keeps settings in this file instead of `~/.kraken-native/settings.properties`. Handy for a separate test profile |
| `kraken.mappings=<dir>` | Reads game mappings from this folder instead of the bundled ones. See [Mapping a new game build](updater.md) |
| `kraken.hook.projectileStep=true` | Has the native hook report every projectile's movement each frame. Off by default, as it's about 50 messages a second per projectile |

Pass them to Gradle with `-D`. In PowerShell, quote each one, or PowerShell splits it at the first dot:

```powershell
./gradlew :client:run "-Dkraken.settings=D:\kraken\test.properties"
```

## What Happens When It Starts

1. Kraken opens with a splash screen, **Launching Old School RuneScape…**, and starts the game.
2. When the game's window appears, Kraken takes it into its own window.
3. Kraken attaches to the game. It reads the game's version and looks for a matching mapping, the file that tells it
   where everything is in the game's memory. The status line on the Kraken tab shows how this goes.
4. Once attached, the plugins load and the ones you've switched on start.
5. Kraken injects its native hook into the game. The hook tells Kraken the moment something happens, draws
   overlays and highlights inside the game, and runs menu actions. See [The native hook](#the-native-hook).

If there's no mapping for your game version, the game still runs inside Kraken, but no plugins load. See
[Mapping a new game build](updater.md).

## The Window

* The title bar is Kraken's own. Drag it to move the window, double-click it to maximise, and drag the window to a
  screen edge to snap it, as with any Windows window.
* The window's title, shown on the taskbar and in Alt+Tab, reads `Kraken - <player name>` while you're logged in,
  so you can tell several windows apart. Turn this off with **Player name in title**.
* Kraken keeps the game sized to fill its area. Click into the game to give it the keyboard.
* Closing Kraken closes the game, and closing the game closes Kraken. By default Kraken asks first while you're logged
  in (see **Confirm before closing** below).

## The Sidebar

The strip on the right has four tabs:

| Tab | What it holds |
|---|---|
| **Kraken** | The game's process ID, build, size and frame rates, and whether Kraken is attached |
| **Plugins** | Every plugin, with a switch and a settings page for each |
| **Dev Tools** | Tools that show what Kraken reads from the game. See [Dev tools](dev-tools.md) |
| **Settings** | Kraken's own settings |

Click a tab to open its panel and click it again to close it. Drag the panel's left edge to make it wider, and
double-click the edge to put it back. By default, opening a panel widens the window instead of shrinking the game;
turn off **Grow window for panels** to change that. Kraken reopens the panel you last had open.

### Kraken

| Row | What it shows |
|---|---|
| **PID** | The game's process ID |
| **Build** | The first 16 characters of the game binary's SHA-256, which tell builds with the same version apart |
| **Game size** | The game area's size in pixels |
| **Game FPS** | The game's frame rate, and the cap while one applies. Needs the native hook |
| **Overlay FPS** | How many overlay frames Kraken draws a second, or `idle` while there's nothing to draw |

The status line underneath reads **Attached · client 241.3** once everything works. If it turns red, it says why;
see [Troubleshooting](troubleshooting.md).

### Plugins

Type in the search box to filter by name, description or tag. Each row has:

* a switch to turn the plugin on or off, which is remembered for the next launch;
* a cog, for plugins with settings, that opens the plugin's settings page;
* a red **failed** label if the plugin couldn't start. Hover over it for the reason, and see the console for the
  details. A plugin that fails to start is switched off.

Hover over a plugin's name to read its description. Plugins only appear once Kraken has attached to the game. The
built-in plugins are described in [Plugins](plugins.md).

### Settings

Settings pages, Kraken's own and each plugin's, work the same way. Hover over a setting's name for a description.
Changes apply at once and are saved. **Reset** at the bottom puts every setting on the page back to its default.

**Window**

| Setting | Default | What it does |
|---|---|---|
| Game size | 765 × 503 | The game's size in pixels. Kraken opens at this size unless it remembers where it was left, and resizes to it when you change it |
| Remember size and position | On | Reopens where Kraken was last left, at the same size, maximised if it was |
| Lock window size | Off | Stops the window being resized by dragging its edges |
| Grow window for panels | On | Widens the window when a panel opens instead of shrinking the game |
| Always on top | Off | Keeps Kraken above other windows |
| Player name in title | On | Adds the logged-in player's name to the window title |
| Confirm before closing | While logged in | When to ask before closing: **Never**, **While logged in** or **Always** |

**Frame rate**

| Setting | Default | What it does |
|---|---|---|
| Show FPS | Off | Draws the game's and the overlays' frame rates at the top of the game |
| Limit FPS when unfocused | Off | Holds the game to a lower frame rate while another app has focus, to save power. Needs the native hook |
| Unfocused frame rate | 20 fps | The frame rate to hold the game to while unfocused |

**Overlays**

| Setting | Default | What it does |
|---|---|---|
| Frame rate | 30 fps | How often plugin overlays are redrawn, up to 50. Each frame reads the game, so higher costs more |
| Font | Regular | The font overlay panels use: **Regular**, **Bold** or **Monospaced** |
| Font size | 12 px | The size of overlay text |
| Panel background | Translucent dark brown | The colour behind overlay panels |

## The Console

The console along the bottom shows Kraken's log, the plugins' messages and the game's own output. Warnings and errors
are counted in its header. While it's collapsed, the header shows the latest message.

* Click the header, or the arrow, to collapse or expand it. Drag the strip above it to change its height.
* **Follow new output** keeps it scrolled to the newest line.
* **Copy all** copies the whole log, which is the thing to attach to a bug report.
* **Clear** empties the view.

It keeps the last 5,000 lines. Kraken doesn't write a log file.

## Overlays

Plugins draw overlays over the game: panels of text, like the tick counter, and outlines on things in the scene.

While the native hook is connected, overlays are drawn inside the game's own frame, so they're part of the game's
picture, in recordings of the game window too. Without the hook, Kraken draws them in a transparent window laid over
the game, which lets clicks through to the game underneath.

### Moving and Resizing Overlays

1. Hold **Alt** with the pointer over the game. Every overlay you can move is outlined in yellow.
2. Drag an overlay to move it.
3. Drag an overlay's edge or corner to resize it. Only panels can be resized.
4. Right-click an overlay, still holding Alt, to put it back in its default spot and size.

Kraken remembers where you put each overlay. Outlines on things in the scene stay with what they outline, so they
can't be moved.

### Frame Rates

With **Show FPS** on, a small panel at the top of the game shows two rates:

* **Game**: the frames the game draws each second, counted by the native hook. It shows `–` without the hook.
* **Overlays**: the overlay frames Kraken draws each second, up to the **Frame rate** in the Overlays settings.

## The Native Hook

The native hook, `kraken_hook.dll`, is a small library Kraken loads into the game. Without it Kraken can only read the
game's memory from outside, about 50 times a second. With it, Kraken can:

* hear about events the moment the game raises them, instead of on its next read;
* draw overlays inside the game's frame;
* have the game draw its own highlights (model and tile outlines, through walls if you like);
* run menu actions, the [Action Tester](plugins.md#action-tester) plugin's job;
* report the game's scripts as they run;
* cap the game's frame rate while Kraken isn't focused, and count its frames.

The hook is built from C++ as part of the client build, which needs a C++ compiler (see the
[README](../README.md#prerequisites)). Kraken carries on without it if it can't be loaded: events arrive a little
later, overlays move to the window over the game, and the rest of the list above isn't available.

The console says how it went. When it works you'll see:

```
Injecting native event hook into pid <pid> (<n> slots for client 241.3)
Native event hook connected from /127.0.0.1:<port>
Native event hook installed <n> of <n> requested hooks (...)
```

If the game drops the connection, Kraken goes back to reading on its own until the hook reconnects, which it tries
every second.

## Files Kraken Writes

| Where | What |
|---|---|
| `~/.kraken-native/settings.properties` | Every setting: Kraken's, each plugin's, which plugins are on, where overlays are, the window's last size and place. Change it with `kraken.settings` |
| Windows registry, `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\com\kraken\client\ui\devtools` | Which dev tools are on, their options and window positions |
| `%TEMP%\kraken_hook*.dll` | A copy of the native hook, extracted on every launch. The game holds it open, so the copy can't always be deleted when Kraken exits; old ones are safe to delete |

`settings.properties` is a plain text file. Close Kraken before editing it by hand, since Kraken writes it while
running.
