<!-- PROJECT LOGO -->
<br />
<div align="center">
  <a href="https://kraken-plugins.com">
    <img src="client/src/main/resources/kraken.png" alt="Logo" width="128" height="128">
  </a>

<h3 align="center">Kraken Native</h3>

  <p align="center">
    A RuneLite-style client, API and offset updater for the official native Old School RuneScape client.
    <br />
    <a href="docs/README.md"><strong>Read the docs »</strong></a>
  </p>
</div>

---

Kraken Native brings RuneLite-style plugins to the official C++ client (`osclient.exe`) that ships with the Jagex
Launcher. It starts the game inside a window of its own, with a sidebar of plugins, dev tools and settings, and reads
the game's state through a Java API shaped like RuneLite's. Plugins draw overlays and highlights in the game, and
react to events such as `GameTick`, `NpcSpawned` or `StatChanged`.

This project is not meant for "botting", automation or rule-breaking purposes, and any material provided by this
project or its documentation is purely for educational purposes only. Kraken Native is not affiliated with or endorsed
by Jagex.

Use at your own risk. The developers are not responsible for any consequences resulting from the use of this software.

## What's Inside

| Part | Status | What it does |
|---|---|---|
| [Client](docs/client.md) (`client/`) | Proof of concept | Launches the game, embeds its window and hosts the plugins, overlays and [dev tools](docs/dev-tools.md) |
| [API](docs/api.md) (`api/`) | In progress | Reads the game from outside its process: players, NPCs, objects, inventories, interfaces, [events](docs/events.md) and more |
| [Updater](docs/updater.md) (`updater/`) | Working | Maps each new game build, so the API knows where to find everything in memory |
| [Native hook](docs/client.md#the-native-hook) (`kraken_hook.dll`) | Working | Loaded into the game by the client, to report events as they happen, draw in the game and run menu actions |

The repository includes the mapping for game version 241.3. When the game updates, [run the updater](docs/updater.md)
to make one for the new build.

## Prerequisites

* Windows 10 or 11, 64-bit, with Old School RuneScape installed through the [Jagex Launcher](https://www.jagex.com/launcher).
* [Java 21](https://adoptium.net/). The Gradle wrapper is included.
* [Visual Studio Build Tools](https://visualstudio.microsoft.com/downloads/) with the **Desktop development with C++**
  workload, to build the native hook. You can [build without it](#without-a-c-compiler).

The [updater](docs/updater.md) needs [uv](https://docs.astral.sh/uv/) and [Ghidra](https://github.com/NationalSecurityAgency/ghidra)
as well, but only when you're mapping a new game build.

## Quick Start

```powershell
git clone https://github.com/Kraken-Plugins/kraken-native.git
cd kraken-native
./gradlew :client:run
```

The first run downloads its dependencies and builds everything, which takes a few minutes. Then Kraken starts the
game from the Jagex Launcher's install folder, takes its window in, and attaches to it. When the **Kraken** tab's
status line reads **Attached**, open the **Plugins** tab and switch a plugin on;
[Tick Counter](docs/plugins.md#tick-counter) is a good first one.

Don't start the game from the launcher first: Kraken starts its own copy, and closing Kraken closes it.

### Logging In

The Jagex Launcher normally hands your Jagex account session to the game. When Kraken starts the game, you hand it over
instead, through three environment variables. See [Logging in with a Jagex account](docs/jagex-accounts.md).

### Launch Options

```powershell
./gradlew :client:run --args="D:/Games/OSRS/osclient.exe"          # a copy of the game somewhere else
./gradlew :client:run --args="--plugin com.example.MyPlugin"       # load a plugin from the classpath
./gradlew :client:run --args="--developer-mode"                    # also load developer plugins
```

More options and settings are in [Using the client](docs/client.md).

### Without a C++ Compiler

```powershell
./gradlew :client:run -x buildNativeHook
```

This skips the native hook. Kraken still reads the game and runs plugins, but overlays are drawn in a window over the
game, the game's own highlights and menu actions aren't available, and events arrive a little later.

### When the Game Updates

Each game build needs a mapping. When Jagex ships a new client, Kraken's status line says there's no mapping for it,
and no plugins load. Make one with the updater, then run Kraken again:

```powershell
cd updater
uv run native-updater run --previous ../api/src/main/resources/mappings/241.3.json
```

[Mapping a new game build](docs/updater.md) covers setting up Ghidra, reading the results and checking the mapping
against the game.

## Writing Plugins

Plugins are written as RuneLite plugins are. A plugin that logs your Hitpoints whenever they change:

```java
@Log(topic = "kraken")
@PluginDescriptor(name = "Hitpoints Logger", description = "Logs your Hitpoints when they change")
public class HitpointsLoggerPlugin extends Plugin {

    @Subscribe
    public void onStatChanged(StatChanged event) {
        if (event.getSkill() == Skill.HITPOINTS) {
            log.info("Hitpoints: " + event.getBoostedLevel());
        }
    }
}
```

Put it in a package under `client/src/main/java/com/kraken/client/plugins/` and Kraken finds it at launch.
[Writing a plugin](docs/writing-plugins.md) covers settings, overlays, highlights and the client thread, and the
[API guide](docs/api.md) covers what you can read.

## Documentation

* [Using the client](docs/client.md)
* [Logging in with a Jagex account](docs/jagex-accounts.md)
* [Plugins](docs/plugins.md)
* [Dev tools](docs/dev-tools.md)
* [Mapping a new game build](docs/updater.md)
* [Writing a plugin](docs/writing-plugins.md)
* [The API](docs/api.md)
* [Events](docs/events.md)
* [Troubleshooting](docs/troubleshooting.md)

How it all works inside, for contributors: the [developer documentation](docs/developer/README.md).

## Acknowledgements

The API and client are modelled on [RuneLite](https://github.com/runelite/runelite). The API's classes, method names
and events, and the client's plugin, config and overlay systems, follow RuneLite's so that writing a plugin feels the
same. `com.kraken.api.gameval` is generated from RuneLite's gameval constants and keeps their BSD 2-Clause license
notice. Thanks to the RuneLite developers.

## License

Kraken Native is licensed under the [GNU General Public License v3.0](LICENSE).
