# Releasing

How the Build workflow tests and packages Kraken, and how to publish a release that players download and run without
Java, Gradle or a checkout.

## What the Workflow Does

[`.github/workflows/build.yml`](../../.github/workflows/build.yml) runs on every push to `master`, every pull request,
every `v*` tag, and on demand (**Actions** > **Build** > **Run workflow**). Its first job runs on a Windows runner,
which has Visual Studio's C++ tools, CMake and WiX Toolset 3, with Temurin 21:

1. `gradlew.bat build` runs the API's and the client's tests and builds `kraken_hook.dll` with the `msvc` preset,
   which the client jar carries.
2. `:client:packageRelease` packages the app (see [Packaging](#packaging)) into `client/build/release/`.
3. Those files are uploaded as the run's artifact, `kraken-<version>-windows-x64`, kept for 14 days (90 for a tag).
   Download it from the run's summary page to try a build before releasing it. If a test fails, the test reports are
   uploaded as `test-reports` instead.

On a tag, a second job on Linux downloads the files, writes `SHA256SUMS.txt` and publishes the GitHub release. It
runs only once the first job has passed, so a release always carries files that passed the tests.

## What a Release Holds

| File | What it is |
|---|---|
| `Kraken-<version>-windows-x64-setup.exe` | Installs Kraken for the current Windows user, in `%LOCALAPPDATA%\Kraken` unless they pick another folder, with a Start menu shortcut and, if they want, a desktop one. Needs no administrator rights. A newer installer replaces the installed version |
| `Kraken-<version>-windows-x64.zip` | The same app without installing: unzip it anywhere and run `Kraken\Kraken.exe` |
| `kraken-api-<version>.jar` | The API on its own |
| `SHA256SUMS.txt` | The SHA-256 checksum of each file |

The release notes say how to install, list the game versions the bundled mappings cover, and end with the notes
GitHub generates from the pull requests and commits since the last release. Edit them on the release page afterwards
if needed.

## Cutting a Release

1. Check that `master`'s latest Build run passed.
2. Tag the commit and push the tag:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

3. The Build workflow runs for the tag. When it passes, the release appears under **Releases**.

Tags look like `v1.2.3`, or `v1.2.3-beta.1` for a pre-release, which GitHub marks as one. The workflow fails on any
other tag. It passes the tag without the `v` to Gradle as `-Pversion`, so the file names and the version on the
**Kraken** tab show it. Every other build takes its version from `gradle.properties`.

Re-running a tag's workflow uploads its files over the existing release's.

Windows Installer reads only the numbers of a version, so `1.2.0-beta.1` and `1.2.0` both install as 1.2.0. Give
each release its own numbers; testers of a beta may have to uninstall it before installing the final release.

Each release can read only the game versions it carries mappings for (`api/src/main/resources/mappings/`). When the
game updates, add the new mapping (see [Mapping a new game build](../updater.md)) and cut a release, so players get it.

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
| `packageInstaller` | Has `jpackage --type exe` wrap the folder in a per-user installer |
| `packageRelease` | Collects the zip, the installer and the API jar into `build/release`, named for the release |

Things to keep in mind when changing them:

* **Runtime modules.** jdeps can't see a module a library reaches only by reflection or a service loader. Such a
  module goes in `extraModules`; `jdk.crypto.ec` is there because HTTPS servers need its ciphers. A missing one shows up
  as a `NoClassDefFoundError` or a missing provider in the packaged app that `:client:run` doesn't have. Check a new
  dependency by running `packageImage` and starting `Kraken.exe`.
* **The version.** `jpackage` and Windows Installer take up to three numbers, so the app version is the leading
  numbers of the project version: `0.1.0-SNAPSHOT` packages as 0.1.0.
* **The upgrade UUID.** `upgradeUuid` tells Windows Installer that every Kraken installer is the same product, so a
  newer one replaces the installed version. Changing it makes new installers install beside the old one.
* **The icon.** `client/src/packaging/kraken.ico` holds `client/src/main/resources/kraken.png` at 16 to 256 pixels.
  Regenerate it when the logo changes:

  ```bash
  python -c "from PIL import Image; Image.open('client/src/main/resources/kraken.png').convert('RGBA').save('client/src/packaging/kraken.ico', sizes=[(s, s) for s in (16, 20, 24, 32, 40, 48, 64, 128, 256)])"
  ```

* **Java options.** The launcher passes Java the options in `app/Kraken.cfg`'s `[JavaOptions]` section, which
  `packageImage`'s `--java-options` would add to. The app needs none today.

## Before Players Can Download It

* **Who can see it.** A release's files are visible to the people who can see the repository. While the repository is
  private, players without access can't download them.
* **Signing.** The launcher and the installer aren't code-signed, so Windows SmartScreen warns about an unknown
  publisher the first time they run, until the player chooses **More info** > **Run anyway**. Signing them needs a
  code-signing certificate and a `signtool` step after `packageImage` and after `packageInstaller`.
