# Releasing

How the Build workflow tests and packages Kraken, and how to publish a release that players download and run without
Java, Gradle or a checkout.

## What the Workflow Does

[`.github/workflows/build.yml`](../../.github/workflows/build.yml) runs on every push to `master`, every pull request,
and on demand (**Actions** > **Build** > **Run workflow**). It has three jobs:

1. **Pick the version** works out the version from [`version.txt`](../../version.txt) and the release tags (see
   [Versions](#versions)).
2. **Test and package** runs on a Windows runner, which has Visual Studio's C++ tools, CMake and WiX Toolset 3, with
   Temurin 21. `gradlew.bat build` runs the API's and the client's tests and builds `kraken_hook.dll` with the `msvc`
   preset, which the client jar carries. `:client:packageRelease` then packages the app (see [Packaging](#packaging))
   into `client/build/release/`, and those files are uploaded as the run's artifact, `kraken-<version>-windows-x64`,
   kept for 14 days. Download it from the run's summary page to try a pull request's build. If a test fails, the test
   reports are uploaded as `test-reports` instead.
3. **Publish the release** runs only for `master`, and only once the build has passed, so a release always carries
   files that passed the tests. On Linux, it downloads the files, writes `SHA256SUMS.txt`, writes the release notes
   (also shown on the run's summary page) and publishes the GitHub release, which tags the built commit
   `v<version>`.

## What a Release Holds

| File | What it is |
|---|---|
| `Kraken-<version>-windows-x64-setup.exe` | Installs Kraken for the current Windows user, in `%LOCALAPPDATA%\Kraken` unless they pick another folder, with a Start menu shortcut and, if they want, a desktop one. Needs no administrator rights. A newer installer replaces the installed version |
| `Kraken-<version>-windows-x64.zip` | The same app without installing: unzip it anywhere and run `Kraken\Kraken.exe` |
| `kraken-api-<version>.jar` | The API on its own |
| `SHA256SUMS.txt` | The SHA-256 checksum of each file |

[`.github/scripts/release-notes.sh`](../../.github/scripts/release-notes.sh) writes the notes: how to install Kraken
and get started, links to the user guides at the release's tag, the game versions the bundled mappings cover, and the
commits since the previous release tag. Commits are grouped by their [conventional commit](https://www.conventionalcommits.org/)
type: `feat` under Features, `fix` and `perf` under Fixes, everything else (including commits with no type) under
Other Changes, with the scope in bold and a `!` breaking change marked **Breaking:**. Merge commits are left out; the commits they bring in are listed. Edit the notes
on the release page afterwards if needed.

## Versions

`version.txt` holds the release line, such as `0.1.0`.
[`.github/scripts/release-version.sh`](../../.github/scripts/release-version.sh) turns it into the version of each
build:

* The first release of a line is the version as written. Each release after it is the next patch above the highest
  `v<major>.<minor>.*` tag: with `2.1.0` in the file, pushes release 2.1.0, 2.1.1, 2.1.2 and so on.
* To start a new line, change the file to `2.2.0` or `3.0.0` and push. That push releases 2.2.0 (or 3.0.0), and the
  ones after it 2.2.1, 2.2.2 and so on. A patch other than 0 works too: `2.2.5` releases 2.2.5, then 2.2.6.
* A commit that already has a release tag keeps its version. Running the workflow again for it, by re-running the run
  or with **Run workflow** on `master`, republishes that release's files and notes rather than making a new one.
* Only `master`'s newest commit is released. A run for an older commit that was never released, such as a re-run of a
  failed run after more pushes, builds it as a `-SNAPSHOT` and leaves the release to the newest commit's run.
* Builds that aren't released, pull requests and other branches, get the version `master` would release next with
  `-SNAPSHOT` appended, such as `2.1.3-SNAPSHOT`. Local builds get `version.txt`'s version with `-SNAPSHOT`
  (`settings.gradle` sets it unless `-Pversion` is passed).

The workflow passes the version to Gradle as `-Pversion`, so the file names and the version on the **Kraken** tab show
it. `version.txt` must hold three numbers without leading zeros; the workflow fails on anything else.

## Cutting a Release

Push or merge to `master`. When the run passes, the release appears under **Releases**, marked as the latest. To push
without releasing, put `[skip ci]` in the commit message: GitHub then runs no workflow for that push, and the next push
that runs one lists its commits as well.

`master`'s runs go one at a time, so each one sees the tag of the release before it. If several pushes arrive while a
run is going, GitHub runs only the newest of the waiting ones, whose release lists the skipped pushes' commits too.

If a release's run fails, fix the cause and push again, or re-run the failed jobs. A run that stopped while uploading
leaves a draft release, which the next run for the same version deletes before publishing its own. Publishing fails
rather than replace the files of a release tagged on another commit, which happens if an old run's failed jobs are
re-run after a newer commit took its version: run the workflow on `master` instead.

Each release can read only the game versions it carries mappings for (`api/src/main/resources/mappings/`). When the
game updates, merge the new mapping (see [Mapping a new game build](../updater.md) and the
[Native mappings workflow](mapping-ci.md)): that merge releases it, so players get it.

## Packaging

[`client/packaging.gradle`](../../client/packaging.gradle) builds the app with the JDK's `jpackage`:

```powershell
./gradlew :client:packageImage       # the app folder: client/build/jpackage/image/Kraken/Kraken.exe
./gradlew :client:packageZip         # that folder zipped: client/build/jpackage/zip/
./gradlew :client:packageInstaller   # the setup .exe: client/build/jpackage/installer/ (needs WiX Toolset 3)
./gradlew :client:packageRelease     # all three release files, renamed, in client/build/release/
```

They need the JDK 21 the build compiles with, which carries `jpackage` and `jdeps`, and the C++ toolchain for the DLL,
as every client build does. The installer also needs [WiX Toolset 3](https://github.com/wixtoolset/wix3/releases)
(the runner has 3.14) on `PATH` or in its default folder.

The app folder:

```
Kraken/
  Kraken.exe   starts the bundled Java with the app's classpath and main class
  app/         Kraken.cfg, the client jar (kraken_hook.dll and kraken.properties inside), the API jar (the
               mappings inside) and the libraries
  runtime/     Java 21, cut down by jlink to the modules the jars use
```

The tasks:

| Task | What it does |
|---|---|
| `stageApp` | Copies the client jar and its runtime classpath into `build/jpackage/input` |
| `packageImage` | Runs `jdeps --print-module-deps` over those jars to find the JDK modules they link against, adds the ones in `extraModules`, and has `jpackage --type app-image` build the launcher and a runtime of just those modules. It prints the list |
| `packageZip` | Zips the folder, with the files in a fixed order and without timestamps, so the same inputs give the same zip |
| `installerArt` | Draws the installer's pictures from `kraken.png` into `build/jpackage/installer-art`: `banner.bmp` (493×58, across the top of each page) and `dialog.bmp` (493×312, behind the welcome and finish pages) |
| `packageInstaller` | Has `jpackage --type exe` wrap the folder in a per-user installer, with the logo as the setup `.exe`'s icon and the `installerArt` pictures in its dialogs |
| `packageRelease` | Collects the zip, the installer and the API jar into `build/release`, named for the release |

Things to keep in mind when changing them:

* **Runtime modules.** jdeps can't see a module a library reaches only by reflection or a service loader. Such a
  module goes in `extraModules`; `jdk.crypto.ec` is there because HTTPS servers need its ciphers. A missing one shows up
  as a `NoClassDefFoundError` or a missing provider in the packaged app that `:client:run` doesn't have. Check a new
  dependency by running `packageImage` and starting `Kraken.exe`.
* **The version.** `jpackage` and Windows Installer take up to three numbers, so the app version is the leading
  numbers of the project version: `0.1.0-SNAPSHOT` packages as 0.1.0. Windows Installer allows at most 255 for the
  major and minor numbers and 65,535 for the patch.
* **The upgrade UUID.** `upgradeUuid` tells Windows Installer that every Kraken installer is the same product, so a
  newer one replaces the installed version. Changing it makes new installers install beside the old one.
* **The installer's pictures.** jpackage has no option for the pictures in the installer's dialogs, so by default they
  are WiX's own, a red disc. `packageInstaller` passes `client/src/packaging/windows` as jpackage's `--resource-dir`,
  and the `main.wxs` there replaces jpackage's: it is JDK 21's copy with two `WixVariable` lines added, which point
  WiX at the `installerArt` pictures through the `KRAKEN_INSTALLER_ART` environment variable. jpackage reports
  `Using custom package resource [Main WiX project file]` with `--verbose`. When the build moves to a newer JDK, copy
  that JDK's `main.wxs` (`jimage extract --include regex:.*/resources/main.wxs --dir <dir> <jdk>/lib/modules`) and
  add the two lines again, since jpackage fills in the template's variables itself. The pictures are drawn from
  `kraken.png`, so they follow the logo.
* **The icon.** `client/src/packaging/kraken.ico` holds `client/src/main/resources/kraken.png` at 16 to 256 pixels.
  `Kraken.exe` and the setup `.exe` use it, and Add or Remove Programs shows `Kraken.exe`'s. Regenerate it when the
  logo changes:

  ```bash
  python -c "from PIL import Image; Image.open('client/src/main/resources/kraken.png').convert('RGBA').save('client/src/packaging/kraken.ico', sizes=[(s, s) for s in (16, 20, 24, 32, 40, 48, 64, 128, 256)])"
  ```

* **Java options.** The launcher passes Java the options in `app/Kraken.cfg`'s `[JavaOptions]` section, which
  `packageImage`'s `--java-options` would add to. The app needs none today.

## Before Players Can Download It

* **Who can see it.** A release's files are visible to the people who can see the repository. The repository is
  public, so anyone can download them; if it went private, players without access couldn't.
* **Signing.** The launcher and the installer aren't code-signed, so Windows SmartScreen warns about an unknown
  publisher the first time they run, until the player chooses **More info** > **Run anyway**. Signing them needs a
  code-signing certificate and a `signtool` step after `packageImage` and after `packageInstaller`.
