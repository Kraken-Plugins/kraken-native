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
| Key Remapping | | Not ported: needs [key interception](#key-interception) |
| Menu Entry Swapper | | Not ported: needs [menu edits](#menu-edits) for every setting |

NPC Indicators replaces Kraken's NPC Highlight plugin, and Ground Markers replaces Tile Markers. Neither old plugin's
settings carry over.

Nothing here has been checked against a running game yet.

## Capabilities Needed

Each capability lists the plugins waiting on it, what exists today, and what has to be built.

### Menu Edits

**Waiting:** Menu Entry Swapper (all of it), NPC Indicators (Tag, Un-tag, Tag-All and their colour and style
sub-menus; menu colours), Ground Items (menu colours, quantities, hiding and collapsing rows), Ground Markers (Mark,
Unmark, Label and Color on a tile; Import, Export and Clear on the world map orb).

**Exists:** the API reads the menu (`Client.getMenuEntries`, `MenuReader`), posts `MenuOpened`, `MenuEntryAdded`,
`MenuEntryRemoved` and `MenuOptionClicked` after the fact, and can run a row's own code ([Menu actions](menu-actions.md)).
The mapping has the minimenu's layout: `Minimenu.rows`, `MinimenuEntry.option` and `target` (game strings), the subject
fields, and the `ON_MINIMENU_REBUILD` raise.

**Needed:** while the menu is closed the client rebuilds it every cycle, so edits have to be applied inside the rebuild,
on the game thread, every cycle. The socket round trip to Java is too slow for that, so the hook DLL has to hold the
edits and apply them itself:

* rules pushed from Java, re-applied after each rebuild: recolour or rewrite a row's option or target by subject and
  name, move a row to the top (left-click swaps), drop or deprioritize rows, collapse duplicate rows;
* rows of Java's own, added by subject kind (Tag on NPCs, Mark on tiles), whose click raises an event back to Java
  instead of running a body. That needs a row body in the DLL and a new report type in the
  [protocol](native-protocol.md);
* writes into the client's strings (`MinimenuEntry.option` and `target` are eastl strings: short ones inline, long ones
  allocated by the client's allocator).

The API then gains `MenuEntry.setOption`, `setTarget`, `setDeprioritized`, `onClick`, `Client.createMenuEntry`,
`Client.setMenuEntries` and a consumable `MenuOptionClicked`. The Ground Markers right-click options also need a text
prompt for labels (RuneLite uses the chatbox) and a colour picker.

### Key Interception

**Waiting:** Key Remapping (all of it).

**Exists:** [`KeyManager`](../../client/src/main/java/com/kraken/client/input/KeyManager.java) reads which keys are
held while the game has the keyboard (`GetAsyncKeyState`), and `HotkeyListener`s hear a keybind go down and up. Nothing
can stop or change a key the game receives.

**Needed:**

* In the DLL, a subclass of the game window's procedure (or an in-process keyboard hook) that rewrites `WM_KEYDOWN`,
  `WM_KEYUP` and `WM_CHAR` by a remap table and a blocked-character set pushed from Java, decided on the game thread
  without a round trip.
* The plugin's "Press Enter to Chat" lock: RuneLite injects callbacks into the chat scripts (`blockChatInput`,
  `setChatboxInput`) that don't exist in the native client. The DLL could block typed characters while chat is locked,
  but showing "Press Enter to Chat" in the chat input needs a write to the chatbox widget's text.
* Reads the plugin makes to decide when keys go to the chat: whether a component has a key listener
  (`Widget.getOnKeyListener`), the focused input field (`Client.getFocusedInputFieldWidget`). Neither is mapped.

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

### Entity Hider, Anti Drag, Key Remapping, Menu Entry Swapper

Not ported. Each needs a capability above for its main job: Entity Hider and Anti Drag [writes to the
game](#writing-to-the-game), Key Remapping [key interception](#key-interception), and Menu Entry Swapper
[menu edits](#menu-edits) for every one of its settings.
