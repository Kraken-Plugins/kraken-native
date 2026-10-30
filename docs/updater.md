# Mapping a New Game Build

Kraken reads the game by following memory offsets: where the player list is, how far into an NPC its name sits, which
function draws a highlight, and so on. These move every time Jagex compiles a new client, so each build needs its own
**mapping**, a JSON file named after the client version, such as `241.3.json`.

The repository ships the mappings it has been tested with in `api/src/main/resources/mappings/`. When the Jagex
Launcher installs a newer client, you need a mapping for it, and the **updater** makes one. It reads `osclient.exe`
from disk; it doesn't need the game running.

## When You Need It

If Kraken has no mapping for your client, the game still starts inside Kraken, but nothing reads it: the Plugins tab
says **No plugins loaded**, and the Kraken tab's status line turns red:

```
No mapping for client 241.4. Run the updater for it.
```

The console says why: either there's no `241.4.json` at all, or the file was made from a different `osclient.exe`
(Jagex sometimes ships a new binary without changing the version number). Kraken checks the binary's SHA-256 against
the mapping, so it never reads a build with offsets meant for another.

## What You Need

* [uv](https://docs.astral.sh/uv/getting-started/installation/), which installs the right Python (3.12) and the
  updater's dependencies for you.
* [Ghidra](https://github.com/NationalSecurityAgency/ghidra/releases) 12.1 and a
  [JDK 21](https://adoptium.net/). Ghidra disassembles the client once per build. It takes 10-15 minutes and up to
  12 GB of memory (set `MAXMEM` to change that).
* Disk space in `~/.kraken-native`: the disassembly database alone is about 350 MB per build.

Tell the updater where Ghidra and the JDK are, so it can run the export itself:

```powershell
$env:GHIDRA_INSTALL_DIR = "C:\Tools\ghidra_12.1_PUBLIC"
$env:JAVA_HOME          = "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"   # or GHIDRA_JAVA_HOME
```

## Running It

From the `updater` folder:

```powershell
cd updater
uv run native-updater run
```

The first `uv run` sets up the updater's environment, which takes a few seconds. The run maps the client in the Jagex
Launcher's default install folder; pass `--binary "D:\Games\OSRS\osclient.exe"` to map another copy.

When you're mapping an update, also give it the previous build's mapping:

```powershell
uv run native-updater run --previous ../api/src/main/resources/mappings/241.3.json
```

It reuses what it learned about the old build to find values that moved, and prints every value that changed. This
works best when the old build was mapped on the same machine, so its analysis is in the cache: map the current build
once before the game updates.

## Reading the Result

The run ends with a summary like this:

```
verdicts: 5 PROVEN, 551 ANCHORED, 14 INFERRED, 0 NEEDS_REVIEW, 0 FAILED
wrote ...\api\src\main\resources\mappings\241.3.json (651 values) and ...\analysis.json; exit 0
```

Each value gets a verdict:

| Verdict | Meaning | In the mapping? |
|---|---|---|
| `PROVEN` | Two independent kinds of evidence agree | Yes |
| `ANCHORED` | One strong kind of evidence, such as the game's own Lua API naming it, and every check passes | Yes |
| `INFERRED` | Only weaker evidence, such as the shape of the code around it | Yes |
| `NEEDS_REVIEW` | The evidence disagrees, or a check failed | No |
| `FAILED` | Nothing found | No |

Every value that isn't `PROVEN` or `ANCHORED` is listed with the reason. The exit code sums it up:

| Exit code | Meaning |
|---|---|
| `0` | The mapping is complete: every essential value is `PROVEN` or `ANCHORED`, and nothing failed |
| `1` | Nothing was written: the client couldn't be read, Ghidra isn't set up, or the updater crashed |
| `2` | The mapping was written, but some values were left out, or an essential one is only `INFERRED` |

With exit code `2`, check the summary's list before playing. Most values are only used by one feature, and Kraken
treats those as optional: the feature reads nothing (`-1`, an empty list) without them. If a value Kraken can't do
without is missing, it won't attach at all: the status line says **Could not attach to osclient.exe**, and the console
names the missing value.

## Using the New Mapping

The mapping is written to `api/src/main/resources/mappings/<version>.json`, next to the bundled ones. Kraken bundles
that folder, so the next `./gradlew :client:run` picks it up. If the version number didn't change, the new file
replaces the old one.

To keep your mappings somewhere else, write them there and point Kraken at the folder:

```powershell
uv run native-updater run --out D:\kraken-mappings
./gradlew :client:run "-Dkraken.mappings=D:\kraken-mappings"
```

With `kraken.mappings` set, Kraken reads only that folder, not the bundled mappings.

## Running Ghidra Yourself

Without `GHIDRA_INSTALL_DIR`, `run` stops and prints the exact Ghidra command to run instead, followed by:

```powershell
uv run native-updater build-model --binary <path to osclient.exe>
```

Run both, then run `native-updater run` again. You can also run the export on its own, without setting the
environment variables:

```powershell
uv run native-updater export --ghidra C:\Tools\ghidra_12.1_PUBLIC --java-home "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"
```

The export is done once per build. Later runs reuse it.

## Checking a Mapping Against the Game

`probe` checks a mapping against the running game: with the client open and logged in, it reads values through the
mapping and checks they make sense: the tick counter advances, skill levels match their XP, the local player is
found and has your name, and so on. It only reads, and it
refuses to run unless the running `osclient.exe` matches the mapping's SHA-256.

```powershell
uv run native-updater probe --mapping ../api/src/main/resources/mappings/241.3.json --display-name "<your display name>"
```

It prints PASS, FAIL or SKIP per check and exits with `2` if any check fails. `--display-name` enables the name check.
Run it from PowerShell or cmd, not WSL. The probe is new and hasn't yet been run against a logged-in client, so treat
a failure as something to look into, not proof that the mapping is wrong.

## Where Files Go

| Path | What it holds |
|---|---|
| `api/src/main/resources/mappings/<version>.json` | The mapping (change it with `--out`). The only file written into the repository |
| `~/.kraken-native/store/<sha256>/osclient.exe` | A copy of every client build you've mapped, so an update doesn't lose the old one |
| `~/.kraken-native/cache/<sha256>/` | Ghidra's project and export, `program.db` (the disassembly as a database) and `analysis.json` (the evidence behind every value) |

Set `KRAKEN_NATIVE_HOME`, or pass `--cache <dir>` before the command (`native-updater --cache D:\kraken run`), to keep
these somewhere else.

## Running From WSL

The updater also runs under WSL against the Windows install. It translates `C:\` paths to `/mnt/c/`, and uses your
Windows profile's `.kraken-native`, so Windows and WSL share one Ghidra export.

WSL needs its own Python environment, or it replaces the one Windows uses. Add this to `~/.bashrc` (or `~/.zshrc`)
once:

```bash
uv() { if [[ $PWD == /mnt/* ]]; then UV_PROJECT_ENVIRONMENT=.venv-linux command uv "$@"; else command uv "$@"; fi }
```

Run the Ghidra export and `probe` from Windows.

## More

The updater has more commands for people improving it: `lint` checks the key definitions, `bindings` lists the game's
Lua bindings, and `sql` queries a build's `program.db`. See [`updater/README.md`](../updater/README.md).
