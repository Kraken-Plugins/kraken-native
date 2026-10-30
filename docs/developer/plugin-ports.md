# Plugin Ports

Kraken ports RuneLite's plugins so they behave the same on the native client. This page tracks which are ported, which
of their settings work, and what the client needs before the rest can. It is the to-do list for that work.

## Rules for a Port

* **Same settings.** A port keeps every RuneLite setting, with the same config group, keys, names, defaults and order, so
  a user's RuneLite values carry over.
* **Unsupported settings are marked, not dropped.** A setting the client can't act on yet gets
  [`@Unsupported("reason")`](../../client/src/main/java/com/kraken/client/plugins/config/Unsupported.java). The settings
  page greys it out with a **Currently unsupported** tooltip. Its value is still stored and read.
* **A plugin that needs a missing capability for its main job isn't ported.** It's listed below with what it needs.

To turn on an unsupported setting:

1. Build the capability it needs (see [Capabilities Needed](#capabilities-needed)).
2. Wire it in where the plugin's table says.
3. Remove the setting's `@Unsupported`.
4. Update this page and [Plugins](../plugins.md).

## Status

| Plugin | Package | Status |
|---|---|---|
| Tile Indicators | `plugins.tileindicators` | Ported. Every setting works |
| Ground Markers | `plugins.groundmarkers` | Ported. Marking tiles from the right-click menu doesn't work; a hotkey marks the tile under the mouse instead, and marks import and export through the clipboard |
| NPC Indicators | `plugins.npchighlight` | Ported. Menu tagging and menu colours, minimap names and the outline feather don't work; the hull is the game's model highlight |
| Ground Items | `plugins.grounditems` | Ported. High alchemy values only; menu changes, notifications, despawn timers, ownership and lootbeams don't work |
| Entity Hider | | Not ported: needs [writes to the game](#writing-to-the-game) |
| Anti Drag | | Not ported: needs [writes to the game](#writing-to-the-game) |
| Key Remapping | `plugins.keyremapping` | Ported. Every setting works; the chat input doesn't show "Press Enter to Chat...", typed text isn't cleared on Escape, and an interface's own text field doesn't lift the remapping |
| Menu Entry Swapper | `plugins.menuentryswapper` | Ported. Every setting works; swaps to a sub-option aren't applied or offered, and Shift-click Walk here applies to every object or NPC, including one with a Shift-click swap of its own |

NPC Indicators replaces Kraken's NPC Highlight plugin, and Ground Markers replaces Tile Markers. Neither old plugin's
settings carry over.

Nothing here has been checked against a running game yet; the menu edits and key interception least of all.

## Capabilities Needed

Each capability lists the plugins waiting on it, what exists today, and what has to be built.

### Menu Edits

**Built** ([Menu edits](menu-edits.md)). The hook DLL holds rules the client sends and applies them inside every menu
rebuild, on the game thread: `MenuRule.Edit` (hide, deprioritize, promote, rewrite a row's option or target, report
its clicks), `MenuRule.Swap` (left-click swaps) and `MenuRule.Add` (rows of a plugin's own, with sub-menus, whose
clicks come back as `MenuOptionClicked` and run nothing in the game), each matched by subject, action, id, interface,
tile and text, and by the Shift key. The API has `MenuEntry.setOption`, `setTarget`, `setDeprioritized` and
`onClick`, `Client.createMenuEntry` (with `createSubMenu`), `Client.setMenuEntries`, `Client.getMenuEdits()` for a
plugin's own rules, and `MenuOptionClicked.consume()`. Menu Entry Swapper is built on it.

**Still waiting:** NPC Indicators (Tag, Un-tag, Tag-All and their colour and style sub-menus; menu colours), Ground
Items (menu colours, quantities, hiding and collapsing rows) and Ground Markers (Mark, Unmark, Label and Color on a
tile; Import, Export and Clear on the world map orb) can now be wired as their tables say. Two things to know when
wiring them: an entry setter made from `MenuEntryAdded` takes effect from the next rebuild (about 20 ms later), so a
persistent `MenuRule` from the plugin's settings is better where the rows are known in advance (a recolour of every
highlighted NPC's target, say); and `setMenuEntries` only expresses hidden rows and a new top row, so collapsing
duplicate ground item rows needs a hide rule per duplicate. The Ground Markers options also need a text prompt for
labels (RuneLite uses the chatbox) and a colour picker.

**Not covered:** rules on the rows of a sub-menu other than rewriting their text and copying one to the top; a
consumed `MenuOptionClicked` stopping the game's own row (it has run by then); menus other than the right-click
menu.

### Key Interception

**Built** ([Key interception](key-interception.md)). The hook DLL detours the game window's procedure and rewrites or
drops `WM_KEYDOWN`, `WM_KEYUP` and `WM_CHAR` by a table of modes the client sends
(`KeyManager.setRemapTable`, `setRemapMode`); a mode can block every typed character, and a rule can move to another
mode as its key goes down, so Enter and Escape switch between locked and typing with no round trip. What the hook did
with each key comes back as `KeyReport`s. `Widget.getOnKeyListener()` reads a component's key listener. Key Remapping
is built on it.

**Not covered:** showing "Press Enter to Chat..." in the chat input and clearing the typed text on Escape, both writes
to the chatbox the API doesn't make; the focused input field (`Client.getFocusedInputFieldWidget`), which the Windows
build gives no string or type name to anchor on; a key Kraken itself could hear, as the game still owns the keyboard.

### Writing to the Game

**Waiting:** Entity Hider (all of it), Anti Drag (all of it).

The API is read-only except highlights and menu actions, and [`CLAUDE.md`](../../CLAUDE.md) keeps other Lua functions
that change the client out of scope. These two plugins need that decision changed.

**Entity Hider.** The client's Lua API already hides actors: `osrs.Entity.render3D(bool)` (handler `0x219db0` in 241.3)
and `render2D(bool)` (`0x219ea0`) set flags on the actor, which the mapping has for reading as `Actor.hidden` and
`Actor.render2D`. `ClientOp.renderProjectiles(bool)` (`0x3ae120`) turns all projectiles off. A port would:

* add these as `NativeCall`s the DLL runs on the game thread (or have the DLL write the two flags), as highlights do;
* decide per player and NPC on spawn, and again when interacting, death or the settings change, and show everything
  again on shutdown and reconnect;
* leave out what has no backing yet: party members (no party service), boats (sub-worlds aren't read), and the Nylocas
  death spot animations (no per-graphics-object setter is known).

**Anti Drag.** Needs the drag dead time of item components (`IfType.dragDeadTime`), the client's inventory drag delay,
clearing a component's mouse-repeat listener, and the component a script is running for
(`Client.getScriptActiveWidget`, used in `ScriptPostFired`). None is mapped, and all but the last are writes. The
Shift and Ctrl state it watches is available from `KeyManager`.

### Minimap Positions

**Waiting:** NPC Indicators (names on the minimap), Ground Markers (tiles on the minimap).

**Needed:** `Perspective.localToMinimap`: the minimap's centre on the canvas (from the minimap component's bounds), its
rotation (the camera yaw, if the native minimap follows it; unconfirmed) and its zoom (not located). Then an
`OverlayUtil.renderMinimapLocation` helper.

### Model Shapes

**Waiting:** NPC Indicators' hull and outline feather.

**Needed:** actor model vertices, for `Actor.getConvexHull`. The render model the client builds each frame isn't
decoded. Until then the hull setting draws the game's own model highlight, filled, which looks close; the game's outline
has no feather setting.

### Notifications

**Waiting:** Ground Items (notify for highlighted drops).

**Needed:** a `Notifier` in the client: a tray notification, sound or window flash. It needs nothing from the game.

### Ground Item Details

**Waiting:** Ground Items (despawn timers, ownership filter, lootbeams).

**Needed:** each ground item's spawn tick, despawn and visible-to-others times, and its ownership (the mapping has only
`GroundItem.id` and `count`). Lootbeams draw a model in the scene, which has no native path yet.

### Grand Exchange Prices

**Waiting:** Ground Items' GE price display and value modes.

Ground Items uses high alchemy values only, by choice, so it makes no network calls. A price source, such as the OSRS
Wiki's real-time prices API, would back the GE options.

### Instances and Orientation

**Waiting:** NPC Indicators' respawn timer, Ground Markers in instances.

**Needed:** whether the scene is instanced and its template chunks (`Client.isInInstancedRegion`,
`WorldPoint.toLocalInstance`), and an actor's facing (`Actor.getOrientation`). Without them, marks saved for a region
don't show in an instance built from it, and the respawn timer ignores the tile behind an NPC that moved as it spawned.

## Plugin by Plugin

### Tile Indicators

Every setting works.

| Feature | Backed by |
|---|---|
| Destination tile | `Client.getLocalDestinationLocation()`: `Client.destination` → `Destination.x`, `z`, mapped from the Walk here body's destination setter (`menuops.py: walk_destination`) |
| Hovered tile | `WorldView.getSelectedSceneTile()`: the tile of the menu's Walk here row, which the client rebuilds from the mouse each cycle |
| True tile | `Player.getWorldLocation()` |

### Ground Markers

Marks are stored as in RuneLite: `groundMarker.region_<id>`, a JSON list of `{regionId, regionX, regionY, z, color,
label}` with colours as `#AARRGGBB`, so RuneLite exports import as they are.

| Setting or feature | State | Needs | Where to wire it |
|---|---|---|---|
| Tile color, Border width, Fill opacity | Works | | |
| Draw tiles on minimap | Unsupported | [Minimap positions](#minimap-positions) | A minimap overlay over `GroundMarkerPlugin.getPoints()` |
| Show import/export/clear options | Unsupported | [Menu edits](#menu-edits) on the world map orb | `GroundMarkerPlugin.startUp`/`onConfigChanged`; the buttons call the same import, export and clear |
| Mark, Unmark, Label, Color (Shift + right-click a tile) | Missing | [Menu edits](#menu-edits), a text prompt and a colour picker | `GroundMarkerPlugin`: an `onMenuEntryAdded` on the Walk here row using `markHoveredTile`'s logic |
| Marks in instances | Missing | [Instances](#instances-and-orientation) | `GroundMarkerPlugin.loadPoints` |
| Mark hovered tile (hotkey), Import, Export, Clear loaded markers | Kraken's own | | Stand in for the menu options |

### NPC Indicators

Tile, true tile, south-west tile, south-west true tile and names are drawn by the plugin's overlay, each NPC in its
own colours. Outline and hull are the game's own model highlights, on one NPC channel per style
(`NpcHighlightChannels`), drawn through walls; without the native hook the overlay outlines those NPCs' tiles instead.

| Setting or feature | State | Needs | Where to wire it |
|---|---|---|---|
| NPCs to highlight (with `*` wildcards), tile styles, colours, border width, names above NPCs, ignore dead NPCs, ignore pets, respawn timer | Works | | |
| Highlight hull | Works, differently | [Model shapes](#model-shapes) for a true hull | `NpcHighlightChannels.modelStyle` fills and outlines the model: RGB from the highlight colour, alpha from the fill colour |
| Highlight outline | Works | | The game's outline, opaque |
| Outline feather | Unsupported | A feather in the game's highlight style (none exists), or [model shapes](#model-shapes) and an outline renderer of Kraken's own | `NpcHighlightChannels.modelStyle` |
| Draw names on minimap | Unsupported | [Minimap positions](#minimap-positions) | A minimap overlay added in `startUp`, drawing NPCs whose `HighlightedNpc.isNameOnMinimap()` is set (it already is) |
| Highlight menu style | Unsupported | [Menu edits](#menu-edits): `MenuEntry.setTarget` | An `onMenuEntryAdded` in `NpcIndicatorsPlugin`: for a highlighted NPC that isn't dying (or with Ignore dead NPCs off), set the target to `recolorMenuTarget(target, style, colour)`, which is ported and tested |
| Dead NPC menu color | Unsupported | [Menu edits](#menu-edits) | The same handler: for a dying NPC with the colour set, `ColorUtil.prependColorTag(Text.removeTags(target), colour)` |
| Tag, Un-tag, Tag-All, Un-tag-All (Shift + right-click) | Missing | [Menu edits](#menu-edits): added rows with callbacks, `KeyManager.isKeyPressed(VK_SHIFT)` on Examine rows | Call `tag(npc)` and `tagAll(npc)` in `NpcIndicatorsPlugin`, which hold the logic |
| Tag color and Tag style sub-menus | Missing | [Menu edits](#menu-edits), a colour picker | Write `highlightcolor_<id>` and `tagstyle_<id>` (`RenderStyle`'s names) through `ConfigManager`, then rebuild; the plugin already reads both |

Respawn timer differences: NPC orientation isn't mapped, so the tile behind an NPC that moved as it spawned isn't
considered; every area counts as non-instanced; and the tick bookkeeping drops RuneLite's `+ 1`, because `GameTick`
is posted after the game's tick counter has moved. Names are drawn a fixed height above the NPC, as the API doesn't
read model heights.

### Ground Items

The settings' decisions (colours, tiers, hiding, ownership, notification text, lootbeam colour, menu text) live in
`GroundItemRules`, so they're tested without a game and ready for the capabilities below. Tile highlights are overlay
polygons, in each item's colour.

Prices are high alchemy values only. The GE price is unknown (0) for everything except coins (1) and platinum tokens
(1,000), so: **Price display** `HA` and `BOTH` show the alchemy value (`BOTH` adds a GE price for platinum tokens) and
`GE` shows nothing for other items; **Highlight value calculation** `HIGHEST` and `HA` use the alchemy value, and `GE`
gives no tier colours; **Hide under value** is decided by the alchemy value.

| Setting or feature | State | Needs | Where to wire it |
|---|---|---|---|
| Item lists (wildcards, `>` and `<` quantities), colours and value tiers, hide under value, show highlighted only, don't hide untradeables, highlight tiles, text outline, item highlight mode's overlay part | Works | | |
| Hotkey (hold to show everything, double-tap to hide everything) | Works | | `GroundItemHotkeyListener` through `KeyManager` |
| Clicking an item's name or its +/- box while holding the hotkey to highlight or hide it | Missing | Mouse clicks and the mouse position over the game | The overlay's boxes and `GroundItemsPlugin`'s list updates |
| Show menu item quantities, Recolor menu hidden items, Deprioritize menu hidden items, Menu highlight mode | Unsupported | [Menu edits](#menu-edits): `MenuEntry.setOption`, `setTarget`, `setDeprioritized` | An `onMenuEntryAdded` for ground item rows: find the item by the entry's tile and id, `rules.updateItemColor(item)`, then apply `GroundItemRules.menuText(option, target, item)` |
| Collapse ground item menu | Unsupported | [Menu edits](#menu-edits): `Client.setMenuEntries` | A per-cycle merge of duplicate rows in `GroundItemsPlugin` (not ported yet) |
| Notify for highlighted drops, Notify tier | Unsupported | [Notifications](#notifications) | `GroundItemsPlugin.onItemSpawned`, which logs `GroundItemRules.notification(item)` to the console as a stand-in |
| Despawn timer | Unsupported | [Ground item details](#ground-item-details): spawn, despawn and visible times | `GroundItemsPlugin.buildGroundItem` (set `spawnTime`, `despawnTime`, `visibleTime`, `isPrivate`); the overlay's timers are ported |
| Ownership filter | Unsupported | [Ground item details](#ground-item-details): ownership | `GroundItemsPlugin.buildGroundItem`, which sets `OWNERSHIP_NONE`; `GroundItemRules.shouldDisplayItem` is applied already |
| Highlighted item lootbeams, Lootbeam tier, Lootbeam style | Unsupported | [Ground item details](#ground-item-details): models drawn in the scene | A renderer over `GroundItemsPlugin.getLootbeams()`, which the plugin keeps up to date |

Labels sit a fixed height above the ground, as the API doesn't read item model heights.

### Menu Entry Swapper

The settings become `MenuRule`s ([`MenuEntrySwapperRules`](../../client/src/main/java/com/kraken/client/plugins/menuentryswapper/MenuEntrySwapperRules.java)):
the built-in swaps are `Swap` rules on option and target text, the Walk here settings `DEPRIORITIZE` edits on a subject
kind while Shift is held, the bank and shop Shift-clicks `PROMOTE` edits on an interface's op, and the swaps the player
picked (stored under RuneLite's keys: `item_<id>`, `npc_<id>`, `object_<id>`, `wornitem_<id>`, `ui_<component>`, and
their `shift` variants) `PROMOTE`, `PROMOTE_SUBJECT` or `DEPRIORITIZE` edits on the subject. The Swap left-click and
Swap shift-click sub-menus are `Add` rules made when an Examine row appears while Shift is held.

| Setting or feature | State | Needs | Where to wire it |
|---|---|---|---|
| Every built-in swap, Shift-click Walk here, bank and shop Shift-clicks, Remove dead NPC menus | Works | | |
| Customizable left- and shift-click on items, NPCs, objects, worn items and interfaces | Works, with differences | | Item and worn item swaps match the row by the item's name, as the hook sees no item ids |
| Swaps to a sub-option (an item's or object's sub-menu) | Missing | A sub row's identity in the entry (the op index of a sub-option row), and its option names from the item or object type | `MenuEntrySwapperRules.storedRule` for the hash-coded values; `MenuEntrySwapperPlugin`'s Swap rows |
| An object's or NPC's own Shift-click swap overriding Shift-click Walk here | Missing | A rule that excludes rows another rule matched | `MenuEntrySwapperRules.rules` |

### Key Remapping

The settings become a table of five modes ([`KeyRemappingTable`](../../client/src/main/java/com/kraken/client/plugins/keyremapping/KeyRemappingTable.java)):
off, locked, locked with a dialog taking numbers, locked with an options dialog, and typing; the plugin picks the mode
from the chatbox's key listener and the dialog widgets on every client tick and follows the hook's own moves through
the key reports.

| Setting or feature | State | Needs | Where to wire it |
|---|---|---|---|
| Camera and F-key remapping, Space and Control, Enter to type, Escape and Enter to lock again, Backspace emptying the input | Works | | |
| "Press Enter to Chat..." in the chat input, clearing the typed text on Escape | Missing | [Writes to the game](#writing-to-the-game): the chatbox input's text and the chat input varc | `KeyRemappingPlugin.onKeyReport`, on the moves to and from typing |
| Lifting the remapping for an interface's own text field | Missing | `Client.getFocusedInputFieldWidget` | `KeyRemappingPlugin.chatboxFocused` |

### Entity Hider, Anti Drag

Not ported. Each needs [writes to the game](#writing-to-the-game) for its main job.
