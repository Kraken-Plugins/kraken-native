# Developer Documentation

How Kraken Native works inside: the updater, the API, the client and the native hook, and how they fit together. For
using Kraken, see the [user guides](../README.md).

Start with [Architecture](architecture.md): it shows the pieces, the two processes and how data moves between them,
and links to the deep dives below.

## The Big Picture

| Page | What's in it |
|---|---|
| [Architecture](architecture.md) | The pieces, the mapping contract, the process boundary, a session end to end, data flows, threads, every place Kraken touches the game, design rules, glossary |
| [Cookbook](cookbook.md) | Step-by-step recipes: tests, a new game build, a mapping key, a Lua-backed read, an event, a hook, a DLL command, a dev tool, a setting |
| [Releasing](releasing.md) | The Build workflow, what a release holds, cutting one, and packaging `Kraken.exe` with `jpackage` |
| [Mapping CI](mapping-ci.md) | How a new game build gets mapped on its own: the n8n watch on the Jagex CDN, the Native mappings workflow, its build cache and its pull request |

## The Parts

| Page | What's in it |
|---|---|
| [Updater](updater.md) | The pipeline from `osclient.exe` to a mapping: the Ghidra export, `program.db`, bindings, provenance, strategies, verdicts, carry-forward, the spec format, tests |
| [Mapping format](mapping-format.md) | Every section of `mappings/<version>.json`, and which code reads it |
| [API internals](api.md) | The memory layer, readers and offset chains, container layouts, config decoding, identity, the poll loop and client thread, testing |
| [Events](events.md) | How events are made: polling trackers, native raises, ordered reports, script events, the event bus |
| [Client internals](client.md) | Startup, the Guice injectors, embedding the game window, attaching, plugins, settings, overlays, dev tools, threads |
| [Native hook](native-hook.md) | `kraken_hook.dll`: injection, threads, the raise detours, the message ring, the pump, the present, the script, menu and key hooks, entity visibility and component writes, building and debugging |
| [Native protocol](native-protocol.md) | The socket protocol between the client and the DLL, byte by byte |

## Features Across the Parts

| Page | What's in it |
|---|---|
| [In-game drawing](in-game-drawing.md) | Highlights drawn by the game itself, and overlays composited into the game's frame through shared memory |
| [Menu actions](menu-actions.md) | How actions run the client's own menu row code and packet helpers on the game thread |
| [Menu action walkthrough](menu-action-walkthrough.md) | One action, attacking an NPC, followed from Java to the game, for readers new to x64, DLLs and the client |
| [Menu edits](menu-edits.md) | How the hook swaps, hides, rewrites and adds rows of the right-click menu by rules, as the game rebuilds it |
| [Key interception](key-interception.md) | How the hook remaps and blocks keys in the game window's procedure, by a table of modes |
| [Entity visibility](entity-visibility.md) | How the hook hides players, NPCs, projectiles and graphics objects by rules it applies every frame |
| [Widget writes](widget-writes.md) | How the hook writes interface components' drag fields, clears listeners and sets texts by id, and how scripts report the components they act on |
| [Reverse engineering](reverse-engineering.md) | Exploring a build to find or fix a value: the primer, Lua bindings, `program.db`, provenance, `analysis.json`, the debug symbols, the probe |
| [Plugin ports](plugin-ports.md) | Which RuneLite plugins are ported, which of their settings work, and what the client needs for the rest |

## By Goal

| You want to… | Read |
|---|---|
| Get oriented | [Architecture](architecture.md), then the page for the part you're touching |
| Support a new game build | The pull request [Mapping CI](mapping-ci.md) opens; else [Mapping a new game build](../updater.md), then [Cookbook](cookbook.md#mapping-a-new-game-build) and [Updater](updater.md) |
| Fix a value the updater got wrong | [Reverse engineering](reverse-engineering.md), [Updater](updater.md) |
| Read something new from the game | [Cookbook](cookbook.md#adding-a-lua-backed-read), [API internals](api.md), [Reverse engineering](reverse-engineering.md) |
| Add or debug an event | [Events](events.md), [Cookbook](cookbook.md#adding-an-event) |
| Change the DLL | [Native hook](native-hook.md), [Native protocol](native-protocol.md) |
| Draw something new in the game | [In-game drawing](in-game-drawing.md) |
| Understand how a menu action reaches the game | [Menu action walkthrough](menu-action-walkthrough.md) |
| Add a kind of menu action | [Menu actions](menu-actions.md) |
| Change the right-click menu, or remap a key | [Menu edits](menu-edits.md), [Key interception](key-interception.md) |
| Hide entities, or write an interface component | [Entity visibility](entity-visibility.md), [Widget writes](widget-writes.md) |
| Work on the client UI, plugins or settings | [Client internals](client.md) |
| Port a RuneLite plugin, or make an unsupported setting work | [Plugin ports](plugin-ports.md) |
| Publish a release, or change what it holds | [Releasing](releasing.md) |

## Conventions

The repository's [`CLAUDE.md`](../../CLAUDE.md) holds the conventions code follows. The ones that matter most when
reading these pages:

* No hard-coded offsets: every address comes from the mapping.
* One RuneLite-shaped API layer: concrete classes, RuneLite's names and meanings, no interfaces, no stubs.
* Comments and Javadoc say what the code does now. Javadoc has `@param` and `@return` for everything, and never
  mentions RuneLite or calls the library "Kraken".
* No banner comments.

Commands for building and testing each module are in [Cookbook](cookbook.md#running-the-tests).
