# The API

The API reads the game from outside its process. It finds the client's data in memory with the offsets in the build's
[mapping](updater.md), and offers it as Java objects: `Client`, `NPC`, `Player`, `Widget`, `WorldPoint`, `GameTick`
and the rest. Its classes, method names and events follow RuneLite's API, so if you've written a RuneLite plugin, most
of it will look familiar. Each getter reads what the game's own Lua plugin API reads.

Everything is read-only, with two exceptions that go through the [native hook](client.md#the-native-hook):
[highlights](#highlights), which have the game outline things itself, [menu actions](#menu-actions), which run a
menu option as a click would, and [menu edits](#menu-edits), which change the right-click menu.

This page is a tour. The Javadoc on each class has the details.

## Getting a Client

In a plugin, inject it:

```java
@Inject
private Client client;
```

Outside the Kraken client, attach to the game yourself; see [Using the API on its own](#using-the-api-on-its-own).

## What You Can Read

| Area | Where to start |
|---|---|
| Game state and login | `client.getGameState()`, `isLoggedIn()`, `getWorldType()`, `getProfileType()`, `isFocused()` |
| Ticks | `getTickCount()` (server ticks, about 600 ms), `getGameCycle()` (client cycles, 20 ms) |
| You | `getLocalPlayer()`, which is null while logged out; `getFollower()` |
| NPCs and players | `getNpcs()`, `getPlayers()`; by index through `getTopLevelWorldView().npcs()` and `.players()` |
| Skills | `getRealSkillLevel(Skill)`, `getBoostedSkillLevel(Skill)`, `getSkillExperience(Skill)`, `getTotalLevel()`; `Experience` converts XP and levels |
| Vars | `getVarpValue(int)`, `getVarbitValue(int)`, `getVarcIntValue(int)`, `getVarcStrValue(int)` |
| Inventories | `getItemContainer(InventoryID.INV)`, `getItemContainers()` |
| Interfaces | `getWidget(group, child)`, `getWidget(packedId)`, `getOpenWidgetGroups()`, `getTopLevelInterfaceId()` |
| Scene | `getScene()`: `getTiles()`, `getTile(plane, x, y)`, `getTileObjects()`, `getGroundItems()` |
| Collision | `getTopLevelWorldView()`: `getCollisionFlags(WorldPoint)`, `lineOfWalk(from, to)`, `lineOfSight(from, to)` |
| Walking and the mouse | `getLocalDestinationLocation()`, the tile you're walking to; `getTopLevelWorldView().getSelectedSceneTile()`, the tile under the mouse (the one Walk here would walk to) |
| Camera and projection | `getCameraX()` and friends, `getCameraPitch()`, `getCameraYaw()`, `getCameraZoom()`; `Perspective` turns game positions into screen points |
| Menu | `getMenuEntries()` (the bottom row first), `isMenuOpen()` |
| Projectiles and spot animations | `getProjectiles()`, `getGraphicsObjects()` |
| Definitions | `getItemDefinition(id)`, `getNpcDefinition(id)`, `getObjectDefinition(id)`, `getVarbit(id)` |
| Scripts | `getIntStack()`, `getObjectStack()` and their sizes, mostly useful in a [script event](events.md#scripts) handler |

A few examples:

```java
Player me = client.getLocalPlayer();
if (me != null) {
    WorldPoint where = me.getWorldLocation();
    int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
}

ItemContainer backpack = client.getItemContainer(InventoryID.INV);
boolean hasCoins = backpack != null && backpack.contains(ItemID.COINS);

for (NPC npc : client.getNpcs()) {
    if ("Goblin".equals(npc.getName()) && !npc.isInteracting()) {
        // a goblin that isn't busy
    }
}
```

### Game Constants

`com.kraken.api.gameval` holds the game's ids by name: `ItemID`, `NpcID`, `ObjectID`, `InterfaceID`, `InventoryID`,
`VarbitID`, `VarPlayerID`, `VarClientID`, `AnimationID`, `SpotanimID`, `SpriteID` and `DBTableID`. They're generated
from RuneLite's constants for the current game revision.

## How Reads Behave

* **Every call reads the game now.** Nothing is cached, so two calls can disagree if the game moved on in between. Read
  on the [client thread](#threads) when several values must agree.
* **A failed read gives an empty value**, not an exception: `-1`, `""`, `null` or an empty list. A logged-out game reads
  as empty too.
* **NPCs and players are handles.** You get the same `NPC` object for the same NPC for as long as it's loaded. Once it's
  gone, its name, location and id keep their last values, and everything else reads empty.
* **Some things are snapshots**: `Widget`, `ItemContainer`, `Projectile`, `GraphicsObject` and `MenuEntry` hold what was
  read when you got them. Get a fresh one to see changes.
* **Tile objects are values.** Two reads of the same wall or tree are `equals`, so you can keep them in a set and remove
  them on despawn.
* **Only the main world is read.** Moving sub-worlds, such as boats, aren't.

### Coordinates

| Type | Meaning |
|---|---|
| `WorldPoint` | A tile in the world: `x` east, `y` north, and `plane` 0 to 3. What the game's world map and wiki use |
| `LocalPoint` | A position in the loaded area, in 128ths of a tile. What drawing and projection use |
| Scene coordinates | A tile in the loaded area, counted from its south-west corner, which moves as you travel |

Convert with `WorldPoint.fromScene(...)`, `WorldPoint.fromLocal(...)` and `LocalPoint.fromWorld(client, worldPoint)`.
`Tile` objects read empty once the loaded area moves, so get them again after a map load.

## Threads

The API reads the game on one thread, the **client thread**. About every 20 ms, or the moment the native hook reports
something, it runs any tasks you've queued, posts events, runs tasks waiting for the end of the tick, and draws the
overlays.

* Event handlers run on the client thread. Keep them quick: a slow handler holds up every event and overlay behind it.
* Getters can be called from any thread, but code that reads several related values, or keeps state that event
  handlers also touch, belongs on the client thread.
* To get there from elsewhere, such as a settings change on the Swing thread, queue a task:

| Method | Runs |
|---|---|
| `invoke(Runnable)` | Now if already on the client thread, otherwise on its next pass |
| `invoke(BooleanSupplier)` | The same, and again each pass until it returns `true` |
| `invokeLater(...)` | On the next pass, always |
| `invokeAtTickEnd(Runnable)` | After the next game tick's events, `GameTick` included |

In a plugin these are on the injected `ClientThread`; on your own, on `GameSession`. There are no versions that return
a value, so hand results back yourself.

## Events

Subscribe to an event with a method annotated `@Subscribe` (`com.kraken.api.eventbus.Subscribe`) that takes it:

```java
@Subscribe
public void onStatChanged(StatChanged event) {
    if (event.getSkill() == Skill.HITPOINTS && event.getBoostedLevel() < 20) {
        log.warning("Low health: " + event.getBoostedLevel());
    }
}
```

A handler for an interface or supertype gets every event that implements it: a `TileObjectSpawned` handler gets all
four kinds of object spawn.

The API makes events by comparing the game's state from one read to the next, and only for events something
subscribes to, so unused events cost nothing. The native hook makes most of them arrive sooner. A few, the script
events, need it. [Events](events.md) lists them all.

Two things to know when tracking what's around you:

* **When an event starts being tracked matters.** Tracking that starts as the API attaches reports what's already
  there, as though you'd just logged in: a spawn for every NPC, player, object and ground item, every skill, every
  inventory. Tracking that starts later begins silently. A plugin switched on mid-game should read what's already
  loaded, such as `client.getNpcs()`, when it starts.
* **A map load spawns everything again.** Each newly loaded area posts a spawn for every object and ground item in it,
  with no despawns for the old one. Clear your lists on `GameStateChanged` with `GameState.LOADING`.

## Highlights

Highlights have the game outline things itself, the way its own plugin API does: around the model, following the
camera, hidden behind walls unless you ask otherwise. They need the native hook.

Take a channel for a category, give it a style, and set what it should light:

```java
HighlightChannel channel = client.getHighlights().channel(HighlightCategory.NPC);
channel.configure(HighlightConfig.DEFAULT
        .withHighlightColour(Color.CYAN)
        .withOutlineWidth(2)
        .withAlwaysOnTop(true));
channel.set(goblins.stream().map(HighlightTarget::npc).toList());
// later
channel.release();
```

| Category | Target |
|---|---|
| `NPC`, `PLAYER` | `HighlightTarget.npc(npc)`, `player(player)` or `player(name)` |
| `LOC` | An object: `HighlightTarget.loc(tileObject)` |
| `OBJ` | A ground item: `HighlightTarget.obj(tileItem)` |
| `TILE` | `HighlightTarget.tile(worldPoint)` |
| `NPC_TYPE`, `LOC_TYPE`, `OBJ_TYPE` | Every NPC, object or ground item with an id: `npcType(id)`, `locType(id)`, `objType(id)` |

`HighlightConfig` sets the colour (its alpha is the fill's opacity), the outline width, and whether to outline or fill
the model and the tile, and draw through walls. `modelOutline(colour, width)` and `tileOutline(colour, width, fill)` are
shortcuts.

`set` sends only what changed, so call it as often as you like. Release the channel when you're done, as the game has
a limited number of them. Without the hook, the channel remembers its style and targets and applies them when the hook
connects; `client.getHighlights().isAvailable()` tells you whether it's connected now.

## Menu Actions

`client.getActions()` runs a menu option the way a click on it would, without moving the mouse: the native hook calls
the game's own code for that menu row on the game thread, so the game sends the same packets and starts the same
walking and facing as for a real click.

```java
Actions actions = client.getActions();
if (actions.isAvailable() && actions.isSupported(MenuAction.NPC_FIRST_OPTION)) {
    actions.menuAction(0, 0, MenuAction.NPC_FIRST_OPTION, goblin.getIndex());
}
```

| Method | What it does |
|---|---|
| `menuAction(entry)`, `menuAction(param0, param1, action, identifier[, itemId[, subOp]])` | Runs a menu entry |
| `click(canvasX, canvasY, ...)` | Sends a mouse click at that point first, as the game does for a real click, then runs the entry |
| `walk(worldPoint)` | Walk here |
| `useItemOn(...)`, `useItemOnItem(...)`, `castSpellOn(...)` | Use an item, or cast a spell, on something |
| `closeInterface()`, `resumePause(componentId, slot)` | Close the open interface; "Click here to continue" |
| `resumeCountDialog`, `resumeNameDialog`, `resumeStringDialog`, `resumeObjDialog` | Answer the game's amount, name, text and item prompts |

Each returns `true` if it was sent. Supported actions are NPC, object, ground item and player options; walking;
interface options with their sub-options; using items and casting spells on things; old-style buttons; closing
interfaces; and click to continue. Examine, walking by the minimap and RuneLite's own menu entries aren't supported.

Actions aren't queued: while the hook isn't connected, they're dropped and return `false`. The
[Action Tester](plugins.md#action-tester) plugin runs every kind from its settings page.

Every action is sent to the game's servers like a real click. Read the disclaimer in the [README](../README.md).

## Menu Edits

`client.getMenuEdits()` changes the right-click menu through the native hook, which applies **rules** every time the
game rebuilds the menu (every client cycle while it's closed), so a swapped option is the left click from the moment a
row shows and a left click never sees the unswapped menu.

```java
MenuEdits edits = client.getMenuEdits();
// "Bank" becomes the left click on anyone with a Talk-to and a Bank option
int bank = edits.add(MenuRule.Swap.options("Talk-to", "Bank", ShiftState.ANY));
// Shift-clicking any NPC walks there instead
int walk = edits.add(MenuRule.Edit.of(MenuMatch.any().kind(MenuSubject.NPC), ShiftState.HELD,
        MenuRule.Edit.Action.DEPRIORITIZE));
// a row of your own above every tile's Walk here while Shift is held
int mark = edits.add(new MenuRule.Add(MenuMatch.any().action(MenuAction.WALK), ShiftState.HELD, false,
        MenuRule.Add.Position.ABOVE, MenuRow.of("Mark tile", "", e -> mark())));
// later
edits.removeAll(List.of(bank, walk, mark));
```

| Rule | What it does |
|---|---|
| `MenuRule.Edit` | On every row a `MenuMatch` names: hide, deprioritize (move to the bottom), promote (swap with the top row), promote within its subject, write the option or target, report clicks |
| `MenuRule.Swap` | Swap a row with the row below it that has another option and the same target: the left-click swaps |
| `MenuRule.Add` | A row of your own (with a sub-menu) next to the rows a match names. Clicking it runs your callback and posts `MenuOptionClicked`, and nothing in the game |

A `MenuMatch` narrows by subject kind, action (a component op with its op number), type id, index, interface, world
tile, and option and target text (`TextMatch`: exact, contains, prefix, suffix or wildcard, with colour tags removed
and case ignored). Each rule says whether it applies with Shift held, not held or either.

The entry setters work too, as they do in RuneLite, with one difference: on an entry you read from the game,
`setOption`, `setTarget`, `setDeprioritized` and `onClick` become a rule on that row from the game's **next** rebuild
(about 20 ms later) for as long as the row shows, so make them from `MenuEntryAdded` rather than `MenuOpened` (an open
menu isn't rebuilt). `client.createMenuEntry(idx)` adds a row of your own next to the row at `idx` the same way, with
`createSubMenu()` for a sub-menu; `client.setMenuEntries(entries)` hides the rows left out and promotes a new top row.

Rules are remembered without the hook and sent when it connects; `isAvailable()` says whether they reach the game now.
[Menu edits](developer/menu-edits.md) has the details.

## Using the API on Its Own

The API also works without the Kraken client, from any Java 21 program on Windows. It isn't published to a Maven
repository yet: depend on the `:api` project from inside this build, or build the jar with `./gradlew :api:jar` and add
it with its dependencies (`net.java.dev.jna:jna` and `jna-platform` 5.14.0, `com.google.code.gson:gson` 2.10.1).

Start the game however you like, then find its process and attach:

```java
ProcessHandle game = ProcessHandle.allProcesses()
        .filter(p -> p.info().command().map(c -> c.endsWith("osclient.exe")).orElse(false))
        .findFirst().orElseThrow();
Path exe = Path.of(game.info().command().orElseThrow());

try (GameSession session = GameSession.attach(game.pid(), exe)) {
    session.eventBus().register(new MyListener());   // before start(), to get the current state as events
    session.start();                                 // starts the client thread

    Client client = session.client();
    System.out.println("Attack level " + client.getRealSkillLevel(Skill.ATTACK));
    Thread.sleep(60_000);
}
```

`attach` picks the bundled mapping for the game's version. To use your own mappings, pass their folder:
`GameSession.attach(pid, exe, Path.of("D:/kraken-mappings"))`. If there's no mapping for the build, or it was made from
a different binary, `attach` throws `UnsupportedBuildException`.

On its own, the API doesn't load the native hook, so highlights, menu actions, menu edits and the script events aren't
available, and other events come from reading the game every 20 ms.

## What's Missing

Some things RuneLite's API has aren't here yet, or can't be read from outside the game:

* Writes to the game other than highlights, menu actions and menu edits: no camera control, no widget changes.
* `ScriptCallbackEvent` and sound effect events. `MenuOptionClicked.consume()` exists but can't stop the game's own
  row, which has already run when the event is posted.
* Sub-worlds such as boats, a world number getter, and a chat history getter (use the `ChatMessage` event).
* NPC and player health ratios: there's `isDead()` and whether a health bar is showing.
* Object rotation, so `GameObject` sizes are as if unrotated.

Getters the API can't back are left out rather than stubbed, so if a method exists, it reads something.
