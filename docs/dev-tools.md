# Dev Tools

The **Dev Tools** tab shows what Kraken reads from the game: NPCs, objects, interfaces, collision, events and more. Use
it to find the names, ids and tiles a plugin needs, or to check the API reads what you expect.

## Using the Panel

Tools are grouped into six sections. Each row has two buttons:

* the **eye** draws the tool over the game, such as names above NPCs or outlines on tiles;
* the **window** opens a live table or tree in a window of its own.

Click a row to read its description and choose its options, such as whether to show ids. **Filter tools** searches
names and descriptions. The button at the top turns off every overlay and closes every tool window at once.

Tools only read the game while they're showing, so leaving them off costs nothing. Kraken remembers which overlays were
on and each tool's options, but not which windows were open. Rows in grey are planned tools; turn off **Show planned
tools** to hide them.

Dev tool overlays are always drawn in the window over the game, even when the native hook draws plugin overlays in
the game, and they can't be moved.

## Actors

| Tool | Shows | Over the game | Window |
|---|---|---|---|
| NPCs | Every NPC loaded around you: index, id, name, tile, ground height, category, interaction target, health bar, follower, uid and creation cycle | Names, ids, tiles | Yes |
| Players | Every player loaded around you, including you: index, name, tile, ground height, queued steps, interaction target, health bar, and whether they're a friend, in your chat or clan, or ignored | Names, tiles | Yes |
| Interacting | Every NPC and player interacting with something, and its target | Yes | Yes |
| Projectiles | Projectiles in flight: spot animation id, source and target, start and end cycles, position and heading | Ids, paths | Yes |
| Graphics objects | Spot animations playing in the scene: id, tile, height, start cycle and frame | Ids, tiles | Yes |

## Client State

| Tool | Shows | Over the game | Window |
|---|---|---|---|
| Client state | Game state, login, tick count, profile type and world type | Yes | Yes |
| Skills | Every skill's level, base level, boost, XP and XP to the next level | | Yes |
| Inventories | Every inventory the game holds (backpack, worn items, bank…) with each slot's item, name and count | | Yes |
| Var inspector | A live log of varp, varbit and varc changes with their old and new values | | Yes |

## Scene

| Tool | Shows | Over the game | Window |
|---|---|---|---|
| Game objects | Scenery within 15 tiles (trees, rocks, tables, decorations, walls and doors) with id, name, options, local location and size | Names, ids | Yes |
| Walls | Walls, doors and fences within 15 tiles, with their ids | Yes | |
| Ground items | Items on the ground within 15 tiles, with id, name, quantity and value | Ids, tiles | Yes |
| Camera | The camera's position, look-at point, pitch, yaw, zoom and scale, and the game's size | Yes | Yes |

## Map & Movement

| Tool | Shows | Over the game | Window |
|---|---|---|---|
| Location | Your world tile, the map square (64 × 64 region) and zone (8 × 8) you're in, your tile within the map square, and your position in the loaded scene | Yes | Yes |
| Zone borders | Outlines the 8 × 8 zones around you | Yes | |
| Map squares | Outlines the 64 × 64 map squares around you | Yes | |
| Tile flags | Collision within 10 tiles: blocked tiles, and walls on the edges they block | Yes | |
| Valid movement | The tiles next to you that you can walk to | Yes | |
| Line of sight | The tiles within 10 that you can see | Yes | |

## Interface

**Widget inspector** browses every open interface as a tree, with the names the game's cache gives them (group 149 is
`INVENTORY`), each widget's children and fields, and the code that fetches it. The selected widget is outlined over the
game, and **Outline everything on screen** outlines them all.

To find a widget, open the inspector's window and press **Pick**, then click the widget in the game. Scroll to step
through the widgets under the pointer, and Shift-click to keep picking. Right-click or Esc stops.

## Diagnostics

**Event log** is a live list of the API's [events](events.md) as they arrive, with a filter for each kind. The busy
ones are off at first: object and ground item spawns, interacting and animation changes, overhead text, var and varc
changes, game ticks, menu entries, and scripts.

It's the quickest way to see which event a plugin should subscribe to, and what it carries.

## Planned

These rows hold spots for tools the API can't back yet:

| Tool | Waiting on |
|---|---|
| World entities | Reading moving sub-worlds such as boats |
| Sound effects | A source for sound events |
