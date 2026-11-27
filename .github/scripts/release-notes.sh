#!/usr/bin/env bash
# Writes the GitHub release notes for a version to stdout: how to install and start Kraken, the game versions the
# bundled mappings cover, and the commits since the previous release tag, grouped by their conventional commit type.
#
# Usage: release-notes.sh <version>. Reads GITHUB_REPOSITORY (owner/name) and runs in a checkout of the released commit
# with its history and tags fetched.
set -euo pipefail

version="$1"
tag="v$version"
repo_url="https://github.com/${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"
docs="$repo_url/blob/$tag/docs"

games="$(find api/src/main/resources/mappings -name '*.json' -printf '%f\n' | sed 's/\.json$//' | sort -V | paste -sd ' ' |
  sed 's/ /, /g')"

# The nearest release tag behind this commit, other than this release's own when the workflow runs again for it
previous="$(git describe --tags --abbrev=0 --match 'v[0-9]*' --exclude "$tag" HEAD 2>/dev/null || true)"

features=()
fixes=()
other=()
conventional='^([A-Za-z]+)(\(([^)]*)\))?(!?): *(.+)$'
while IFS=$'\t' read -r sha subject; do
  type=""
  scope=""
  breaking=""
  text="$subject"
  if [[ "$subject" =~ $conventional ]]; then
    type="${BASH_REMATCH[1],,}"
    scope="${BASH_REMATCH[3]}"
    breaking="${BASH_REMATCH[4]:+**Breaking:** }"
    text="${BASH_REMATCH[5]}"
  fi
  line="* $breaking${scope:+**$scope:** }$text ([${sha:0:7}]($repo_url/commit/$sha))"
  case "$type" in
    feat) features+=("$line") ;;
    fix | perf) fixes+=("$line") ;;
    *) other+=("$line") ;;
  esac
done < <(git log --no-merges --format='%H%x09%s' "${previous:+$previous..}HEAD")

section() {
  local title="$1"
  shift
  if (($# > 0)); then
    printf '### %s\n\n' "$title"
    printf '%s\n' "$@"
    printf '\n'
  fi
}

cat <<EOF
## Install

Kraken runs on Windows 10 or 11, 64-bit, with Old School RuneScape installed through the [Jagex Launcher](https://www.jagex.com/launcher).

1. Download **Kraken-$version-windows-x64-setup.exe** from the files below.
2. Run it. It installs Kraken for your Windows account, with a Start menu shortcut and, if you want, a desktop one, and needs no administrator rights. If Kraken is already installed, it replaces that version.
3. If Windows says it protected your PC, choose **More info**, then **Run anyway**. Kraken isn't signed, so Windows doesn't know its publisher.

Rather not install? **Kraken-$version-windows-x64.zip** holds the same app: unzip it anywhere and run **Kraken\\Kraken.exe**. Both carry their own Java, so there's nothing else to install.

## Getting Started

1. Start **Kraken** from the Start menu. Don't start the game from the Jagex Launcher first: Kraken starts its own copy of the game, takes its window in and attaches to it, and closing Kraken closes it.
2. To log in with a Jagex account, hand Kraken your session before you start it, as [Logging in with a Jagex account]($docs/jagex-accounts.md) shows.
3. When the status line on the **Kraken** tab reads **Attached**, open the **Plugins** tab and switch a plugin on. [Tick Counter]($docs/plugins.md#tick-counter) is a good first one.

[Using the client]($docs/client.md) covers the window, launch options and settings, [Plugins]($docs/plugins.md) lists the built-in plugins, and [Troubleshooting]($docs/troubleshooting.md) helps when something doesn't work.

This release reads game versions $games. When the game updates past them, the status line says there's no mapping for it and no plugins load until a Kraken release that supports the new build is out.

EOF

if [[ -n "$previous" ]]; then
  printf '## Changes Since %s\n\n' "$previous"
else
  printf '## Changes\n\n'
fi
if ((${#features[@]} + ${#fixes[@]} + ${#other[@]} == 0)); then
  printf 'No commits since %s.\n\n' "$previous"
fi
section Features "${features[@]}"
section Fixes "${fixes[@]}"
section "Other Changes" "${other[@]}"
if [[ -n "$previous" ]]; then
  printf '**Full changelog:** [%s...%s](%s/compare/%s...%s)\n\n' "$previous" "$tag" "$repo_url" "$previous" "$tag"
fi

cat <<EOF
## Files

| File | What it is |
|---|---|
| \`Kraken-$version-windows-x64-setup.exe\` | The installer |
| \`Kraken-$version-windows-x64.zip\` | The same app without installing |
| \`kraken-api-$version.jar\` | The API on its own, for reading the game from your own tools |
| \`SHA256SUMS.txt\` | The SHA-256 checksum of each file |
EOF
