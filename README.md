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
| [`client/`](client) | Proof of concept | Java 17 Swing client that launches `osclient.exe` and embeds its window, with a RuneLite-style sidebar and log console |
| [`updater/`](updater) | Working | Python updater that maps a client build and writes `mappings/<version>.json` |
| [`mappings/`](mappings) | Seed build | One mapping per client version (e.g. `241.3.json`): the offsets the API reads, nothing else |
| [`api/`](api) | Designed | Gradle module for the RuneLite-shaped API; see [api-design.md](docs/api-design.md) |
| [`docs/`](docs) | | Design documents for the client surface, updater and API |

The seed build, client 241.3, maps 191 of 198 keys as PROVEN or ANCHORED (the other 7 are INFERRED), with none failed.
[api-gaps.md](docs/api-gaps.md) tracks what the API still needs from the updater.

## Prerequisites

You need:

* Windows, with Old School RuneScape installed through the [Jagex Launcher](https://www.jagex.com/launcher).
* [Java 21](https://adoptium.net/). The Gradle wrapper is included.
* For the updater only: [uv](https://docs.astral.sh/uv/) (it installs Python and PyYAML for you), plus
  [Ghidra](https://github.com/NationalSecurityAgency/ghidra) 12.1 and JDK 21 for the one-off export of each new client
  build.

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

### Logging In With a Jagex Account

When you press Play, the Jagex Launcher logs you in and hands the session to `osclient.exe` in environment variables.
Kraken starts the client itself, so you supply those variables instead: Kraken passes its own environment to the
client unchanged. Without them, the client shows the legacy login screen, which doesn't accept Jagex accounts.

| Variable | What it holds |
|---|---|
| `JX_SESSION_ID` | Your Jagex account's login session. Treat it like a password. |
| `JX_CHARACTER_ID` | The character to log in as. |
| `JX_DISPLAY_NAME` | That character's display name. |

The client also reads `JX_ACCESS_TOKEN` and `JX_REFRESH_TOKEN`, but Jagex account logins don't need them.

To get the values, have RuneLite write them out once:

1. In the Jagex Launcher, choose RuneLite as the Old School client.
2. Run `RuneLite (configure)` from the Start menu and add `--insecure-write-credentials` to **Client arguments**.
3. Pick the character in the Jagex Launcher and press Play. RuneLite saves the variables, under the same names, to
   `%USERPROFILE%\.runelite\credentials.properties`.
4. Copy the values, then remove the flag and delete `credentials.properties`. While the file exists, RuneLite logs in
   with it instead of with the launcher.

Alternatively, start the official client from the launcher and open `osclient.exe`'s properties in
[Process Explorer](https://learn.microsoft.com/sysinternals/downloads/process-explorer); the variables are listed on
its Environment tab.

Then set them in PowerShell and start Kraken from the same window:

```powershell
$env:JX_SESSION_ID   = "<session id>"
$env:JX_CHARACTER_ID = "<character id>"
$env:JX_DISPLAY_NAME = "<display name>"
./gradlew :client:run
```

The variables last until you close that window. In IntelliJ, put them in the run configuration's environment
variables instead. To play a different character on the same Jagex account, change `JX_CHARACTER_ID` and
`JX_DISPLAY_NAME`; a character on another Jagex account needs that account's session ID.

Don't commit or share these values. If they leak, use **End sessions** in your account settings on runescape.com to
invalidate them.

## Mapping a Client Build

When Jagex ships a new client, the updater maps it and writes the contract the API consumes:

```bash
cd updater
uv run native-updater run --binary "<path to osclient.exe>"
```

The first `uv run` creates `updater/.venv` and installs the locked dependencies from `uv.lock`.

This writes `mappings/<version>.json`, named after the client version in the exe's version resource (for example
`241.3.json`). It holds only the values the API reads. The evidence behind them (a verdict per key, the instructions it
came from, Lua binding and fingerprint addresses) goes to `analysis.json` in the build's cache folder. Client binaries,
Ghidra models and those analyses are cached outside the repository in `~/.kraken-native`.
See [updater/README.md](updater/README.md) for the full flow, exit codes, how to write a recipe, and the known gaps.

Run the updater's tests from `updater/`:

```bash
uv run python -m unittest discover -s tests -t .
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
