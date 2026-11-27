# Mapping CI

New builds of the native client get mapped without anyone at a Windows machine. An n8n workflow on the homelab
watches the Jagex CDN, the place the Jagex Launcher installs `osclient.exe` from, and dispatches the
[Native mappings](../../.github/workflows/native-mappings.yml) workflow when the production build changes. The workflow
downloads the build, runs Ghidra and the updater on a Linux runner, and opens a pull request with the new
`mappings/<version>.json` and a report on it. A person still runs the live probe and merges.

```
n8n: Native Client Watch (kube-services/manifests/n8n/workflows/kraken_update_pipeline.json)
  polls jagex.akamaized.net/direct6/osrs-win/alias.json
  a new osrs-win.production catalog: POST .../actions/workflows/native-mappings.yml/dispatches {catalog}
        |
GitHub Actions: Native mappings (ubuntu-latest, 2 cores, 8 GB)
  native-updater fetch      osclient.exe from the CDN, its version, the mapping it has and the one before it
  restore the build cache   this build's and the previous build's binary, Ghidra export and analysis.json
  Ghidra 12.1.4 export      only when the cache has none for this build (about 8 minutes)
  native-updater run        --previous <the mapping before it>.json (about 3 minutes)
  native-updater report     the pull request description
  native-mappings/<version> a pull request against master, marked (needs review) when the run exited 2
```

## The Jagex CDN

Everything is under `https://jagex.akamaized.net/direct6/osrs-win/` and needs no login. `native_updater/cdn.py` reads
it:

| File | What it holds |
|---|---|
| `alias.json` | A JWT whose payload maps `osrs-win.<environment>` to a catalog id: `production` is what the launcher installs, `production-last` the build before it, plus `staging` and previews |
| `catalog/<id>/catalog.json` | A JWT naming the build's metafile and the piece format |
| `metafile/<id>/metafile.json` | A JWT with the `version` (`243.1`), `scanTime` (when Jagex packaged it), the `files` in order with their sizes, and the SHA-256 `digests` of the pieces |
| `pieces/<hh>/<digest>.solidpiece` | A 6-byte header, then gzip. The files lie back to back across the decompressed pieces |

The metafile's `pad` entries describe padding up to each 4 MiB piece boundary that is never stored, so the
decompressed pieces join into the files as they are. Each digest is the SHA-256 of a piece's decompressed bytes
(`hashPadding: false`). The JWT signatures aren't checked: the binary is only read, never run, and every piece is
checked against its digest.

Old catalogs stay on the CDN: on 2026-11-26 `production` was 243.1 and `production-last` 242.3, and the
`osclient.exe` downloaded for each had the SHA-256 in the committed `243.1.json` and `242.3.json`. Ghidra on Linux
exports the same functions and instructions as on Windows, line for line, so the workflow's model of a build is the
one a local run builds.

```bash
uv run native-updater fetch --dest <dir>                               # the production build
uv run native-updater fetch --environment production-last --dest <dir>
uv run native-updater fetch --catalog <id> --dest <dir>
```

`fetch` writes `<dir>/osclient.exe` and prints JSON: the catalog, the version, the SHA-256, the mapping made from this
binary (`mapping`) and the one a run would carry forward from (`previous`: the newest mapping at or below its version
made from another binary), each `null` when there is none.

## What a Run Does

The run first sorts the build into one of three modes:

| Mode | When | What happens |
|---|---|---|
| `new` | Neither master nor a `native-mappings/<version>` branch has a mapping made from this binary (or the run was started with `force`) | Ghidra export unless cached, `run`, save the cache entry, report, open or update the pull request |
| `mapped` | A mapping on master was made from this binary | Nothing if its cache entry is there. Otherwise it maps the build again to make one, and says whether that reproduced the committed mapping |
| `proposed` | The `native-mappings/<version>` branch already holds a mapping for this binary | As `mapped` |

n8n dispatches only when the production catalog changes, so most dispatches are `new`. The scheduled runs (Mondays and
Thursdays) take the production build too, so they map a build n8n missed and otherwise just read the cache. Closing a
pull request without merging it leaves its branch `proposed`; once the branch is deleted, the next scheduled run maps
the build and opens a new one.

`run` exits 0, 1 or 2 as it does locally. On 2 the mapping and analysis are still written, and the pull request opens
with `(needs review)` in its title, as a draft where the repository allows drafts (a private repository on the free
plan doesn't). On 1 the job fails; the run's files are in its artifact either way. A `new` run without the
`NATIVE_MAPPINGS_TOKEN` secret fails before it starts mapping.

Runs for the same catalog (or both the production build's scheduled and empty-catalog runs) take turns; runs for
different catalogs and the seed job don't wait for each other, because GitHub cancels an older pending run in a
concurrency group.

## The Build Cache

Carry-forward needs the previous build's `program.db` and `analysis.json`: it matches functions between the two builds
and re-extracts each value at the instruction that matches its old evidence. Without them, on 243.1, 286 keys would
drop from PROVEN to ANCHORED, 8 that only weak evidence and carry-forward agree on would fall to INFERRED, and the two
only carry-forward finds (`functions.menuLocOp`, `functions.menuLocTargetOp`) would fail. Those carry from build
to build, so each build's `analysis.json` has to outlive the run that made it.

The workflow keeps one GitHub Actions cache entry per build, about 50 MB:
`store/<sha256>/osclient.exe`, `cache/<sha256>/export/` and `cache/<sha256>/analysis.json`. A run restores the entries
for its build and the previous one, and rebuilds `program.db` from the export (about 30 seconds). Entries can't be
replaced, so each key ends in the run id (`native-build-<sha256>-<run id>-<attempt>`) and the restore takes the newest
by prefix.

GitHub drops an entry nobody has read for 7 days, and the repository's caches share 10 GB with Gradle's. The two
scheduled runs a week read both entries and keep them. If the previous build's entry is gone anyway, the run carries
forward by delta propagation only, and the report and a warning say so.

### Seeding the Cache From a Local Run

A run in the workflow can rebuild a missing entry, but only from what it can carry forward itself, so it loses the keys
an older local run carried. When the newest mapping came from a local run, or before the workflow's first run, load
that build's local cache into the workflow's (from Git Bash, with the SHA-256 from the mapping):

```bash
sha=e470c533a26e78793e2bd326dab0ee6424475558a38719595f464a8bca534e4e
tar -C ~/.kraken-native -czf "native-build-$sha.tar.gz" "store/$sha/osclient.exe" "cache/$sha/export/meta.tsv" \
  "cache/$sha/export/functions.tsv.gz" "cache/$sha/export/insns.tsv.gz" "cache/$sha/analysis.json"
gh release create native-build-seed --draft --title "Native build cache seed" \
  --notes "The Native mappings workflow deletes this draft once it has seeded its cache." "native-build-$sha.tar.gz"
gh workflow run native-mappings.yml -f seed_release=native-build-seed
```

The seed job accepts only those files, for one build, checks the binary's SHA-256 and that a committed mapping was
made from it, saves the cache entry and deletes the draft release.

## The Pull Request

The branch is `native-mappings/<version>` and the title `chore(mappings): map native client <version>`. The
description (`native-updater report`, `native_updater/report.py`) holds:

* both builds' SHA-256, link time, key count and Ghidra version, and the CDN catalog;
* the verdict counts next to the previous build's;
* the keys left out of the mapping (FAILED, NEEDS_REVIEW) with their reasons and previous values, and the keys only
  INFERRED;
* the keys whose verdict got weaker or stronger;
* what moved: counts per section, each struct's fields as bands of equal shift in old offset order, the fields that
  moved differently from the fields on both sides of them, and every changed value;
* carry-forward stats, the run's notes, unmatched fingerprints, and the commands to reproduce it.

`report` works on a local run too:
`uv run native-updater report --mapping <new>.json --previous <old>.json --exit-code <code>`.

A re-run for the same version force-pushes the branch and updates the description, unless someone else has committed
to the branch: then it stops, so hand fixes aren't lost. It never marks a draft ready. The commands to reproduce the run
take a same-version rebuild's previous mapping from master, since the branch replaces that file. The pull request runs
[Build](../../.github/workflows/build.yml), whose `NativeProtocolTest` checks every bundled mapping's draw block.

Before merging, run the probe against a logged-in client
(`native-updater probe --mapping ../api/src/main/resources/mappings/<version>.json`), and look into anything the report
flags.

## Setting It Up

1. Add the `NATIVE_MAPPINGS_TOKEN` repository secret: a fine-grained token for `Kraken-Plugins/kraken-native` with
   Contents and Pull requests read/write. `GITHUB_TOKEN` may not open pull requests in this repository, and a pull
   request it opened would not run Build.
2. Seed the cache for the newest mapping, as above.
3. In n8n, import `kraken_update_pipeline.json` and give `Dispatch Native Mapping` a GitHub API credential with Actions
   write on `kraken-native` (see `kube-services/manifests/n8n/README.md`).

To run it by hand: Actions > Native mappings > Run workflow, or `gh workflow run native-mappings.yml` with
`-f catalog=<id>` for a particular build and `-f force=true` to map a build that already has a mapping (a pull request
opens only if the mapping changed). Run it from master: a run elsewhere uses that branch's updater, and its cache
entries stay on that branch.

## Limits

* The repository is private on the free plan: 2,000 Actions minutes a month. A mapping run takes about 15 minutes, a
  cache check about 1.
* The workflow can't run the live probe; that needs Windows and a logged-in client.
* A build Jagex rebuilt without changing its version replaces `<version>.json` in the same pull request branch.
* The updater runs from master, so recipe fixes made for a build must be merged before the workflow can reproduce it.
