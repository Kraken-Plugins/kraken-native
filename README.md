<!-- PROJECT LOGO -->
<br />
<div align="center">
  <a href="https://kraken-plugins.com">
    <img src="client/src/main/resources/kraken.png" alt="Logo" width="128" height="128">
  </a>

<h3 align="center">Kraken Native</h3>

  <p align="center">
    A RuneLite-style client, offset updater and API for the official native Old School RuneScape client.
    <br />
  </p>
</div>

---

# Getting Started

Kraken Native brings the Kraken tooling to the official C++ client (`osclient.exe`) that ships with the Jagex Launcher.
A Java client wraps the game window in a RuneLite-style frame. A Python updater maps the client's struct offsets,
globals and functions for each build. A RuneLite-shaped Java API, designed but not yet built, will read game state
through those mappings.

This project is not meant for "botting", automation or rule-breaking purposes, and any material provided by this
project or its documentation is purely for educational purposes only. Kraken Native is not affiliated with or endorsed
by Jagex.

Use at your own risk. The developers are not responsible for any consequences resulting from the use of this software.

## Project Status

| Module | Status | What it is |
|---|---|---|
| [`client/`](client) | Proof of concept | Java 21 Swing client that launches `osclient.exe` and embeds its window, with a RuneLite-style sidebar and log console |
| [`updater/`](updater) | Working | Python updater that maps a client build and writes `mappings/<sha16>.json` |
| [`mappings/`](mappings) | Seed build | One mapping per client build, keyed by the first 16 hex digits of the exe's SHA-256 |
| [`api/`](api) | Designed | Gradle module for the RuneLite-shaped API; see [api-design.md](docs/api-design.md) |
| [`docs/`](docs) | | Design documents for the client surface, updater and API |

The seed build `166231c8f6bd05c7` maps 210 of 219 keys as PROVEN or ANCHORED, with none failed.
[api-gaps.md](docs/api-gaps.md) tracks what the API still needs from the updater.

## Prerequisites

You need:

* Windows, with Old School RuneScape installed through the [Jagex Launcher](https://www.jagex.com/launcher).
* [Java 21](https://adoptium.net/). The Gradle wrapper is included.
* For the updater only: Python 3.10+ with PyYAML, plus [Ghidra](https://github.com/NationalSecurityAgency/ghidra) 12.1 and JDK 21 for the
  one-off export of each new client build.

## Running the Client

```bash
./gradlew :client:run
```

The client starts `osclient.exe` from the Jagex Launcher's default install folder. To use a different copy, pass its
path:

```bash
./gradlew :client:run --args="D:/Games/OSRS/osclient.exe"
```

What you get:

* The game window embedded in a resizable frame with a custom title bar, which supports dragging, Windows snap,
  minimize, maximize and close. Closing Kraken closes the game, and the other way round.
* A sidebar on the right, like RuneLite's. The Kraken tab shows the client's process ID, build hash and game size.
  Opening a panel widens the window instead of shrinking the game; you can change this in Settings.
* A collapsible log console at the bottom. Anything logged through `java.util.logging` appears there, as does the
  client's own output.

Jagex accounts: `osclient.exe` gets its login session from `JX_*` environment variables set by the Jagex Launcher.
Kraken passes its own environment through to the client. Started outside the launcher, it has no session to hand
over, so the client shows the legacy login screen.

## Mapping a Client Build

When Jagex ships a new client, the updater maps it and writes the contract the API consumes:

```bash
cd updater
pip install pyyaml
python -m native_updater run --binary "<path to osclient.exe>"
```

This writes `mappings/<sha16>.json`, a `<sha16>.report.md` with a verdict for every key, and an updated
`mappings/index.json`. Client binaries and Ghidra models are cached outside the repository in `~/.kraken-native`.
See [updater/README.md](updater/README.md) for the full flow, exit codes, how to write a recipe, and the known gaps.

Run the updater's tests from `updater/`:

```bash
python -m unittest discover -s tests -t .
```

## Roadmap

The API is planned in milestones; see [api-design.md](docs/api-design.md) §12 for the details.

* **A0** Load the mapping, attach to the client and verify its identity, then read `Client` scalars such as the game
  state, tick, skills and varps.
* **A1** Players, NPCs, inventories, ground items, locs, and a tick loop with events.
* **A2** Widgets, collision, tile heights and projection, projectiles and graphics objects.
* **A3** Orientation, hitsplats, overhead text, appearance, chat and other remaining surface.
* **A4** An in-process backend, injected into the client, for tick hooks, native events and actions.

## License

Kraken Native is licensed under the [GNU General Public License v3.0](LICENSE).
