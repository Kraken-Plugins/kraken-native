# The API

The API reads the game from outside its process. It finds the client's data in memory with the offsets in the build's
[mapping](updater.md), and offers it as Java objects: `Client`, `NPC`, `Player`, `Widget`, `WorldPoint`, `GameTick`
and the rest. Its classes, method names and events follow RuneLite's API, so if you've written a RuneLite plugin, most
of it will look familiar. Each getter reads what the game's own Lua plugin API reads.

Everything is read-only, with a few exceptions that go through the [native hook](client.md#the-native-hook):
[highlights](#highlights), which have the game outline things itself, [interactions](#interacting) and
[menu actions](#menu-actions), which run a menu option as a click would, [menu edits](#menu-edits), which change the
right-click menu,
[entity visibility](#entity-visibility), which hides players, NPCs and projectiles, and [widget writes](#widget-writes),
which change a few fields of interface components.

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
| Game state and login | `client.getGameState()`, `isLoggedIn()`, `getWorld()` (the world number), `getWorldType()`, `getProfileType()`, `isFocused()` |
| Ticks | `getTickCount()` (server ticks, about 600 ms), `getGameCycle()` (client cycles, 20 ms) |
| You | `getLocalPlayer()`, which is null while logged out; `getFollower()` |
| NPCs and players | `getNpcs()`, `getPlayers()`; by index through `getTopLevelWorldView().npcs()` and `.players()` |
| Skills | `getRealSkillLevel(Skill)`, `getBoostedSkillLevel(Skill)`, `getSkillExperience(Skill)`, `getTotalLevel()`; `Experience` converts XP and levels |
| Vars | `getVarpValue(int)`, `getVarbitValue(int)`, `getVarcIntValue(int)`, `getVarcStrValue(int)` |
| Inventories | `getItemContainer(InventoryID.INV)`, `getItemContainers()`; `EquipmentInventorySlot` names the worn slots and their components |
| Interfaces | `getWidget(group, child)`, `getWidget(packedId)`, `getOpenWidgetGroups()`, `getTopLevelInterfaceId()`; a widget's `getClickMask()`, with the bits in `WidgetConfig` (what a spell or item selected from it can be used on, for example) |
| Scene | `getScene()`: `getTiles()`, `getTile(plane, x, y)`, `getTileObjects()`, `getGroundItems()` |
| Collision | `getTopLevelWorldView()`: `getCollisionFlags(WorldPoint)`, `lineOfWalk(from, to)`, `lineOfSight(from, to)` |
| Walking and the mouse | `getLocalDestinationLocation()`, the tile you're walking to; `getTopLevelWorldView().getSelectedSceneTile()`, the tile under the mouse (the one Walk here would walk to) |
| Camera and projection | `getCameraX()` and friends, `getCameraPitch()`, `getCameraYaw()`, `getCameraZoom()`; `Perspective` turns game positions into screen points |
| Menu | `getMenuEntries()` (the bottom row first), `isMenuOpen()`; `getPlayerOptions()` and `getPlayerOptionsPriorities()`, the options the server set for right-clicking other players |
| Projectiles and spot animations | `getProjectiles()`, `getGraphicsObjects()` |
| Definitions | `getItemDefinition(id)`, `getNpcDefinition(id)`, `getObjectDefinition(id)` (`getImpostor()` for the object a loc shows as now), `getVarbit(id)` |
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

## Interacting

The interaction methods act on what you already hold, by the option text the player sees. They work out the menu row
the game would build for it and run it through [menu actions](#menu-actions), so they need the native hook too.

```java
npc.interact("Attack");
tree.interact("Chop down");          // a TileObject
coins.take();                        // a TileItem: interact("Take")
player.interact("Trade with");
bankItem.interact("Withdraw-10");    // a Widget
slot.interact("Drop", "All");        // an option with one of its sub-options
slot.useOn(npc);                     // use an inventory item on an NPC
fireStrike.useOn(npc);               // cast a spellbook spell on an NPC
```

| Subject | Options it matches against (`getActions()`) | Numbers |
|---|---|---|
| `NPC` | Its shown type's actions, so a transformed NPC's are the ones it shows as | 1-5 |
| `Player` | `client.getPlayerOptions()`: the options the server set, the same for every player | 1-8 |
| `TileObject` | Its impostor's actions; `getName()` is the impostor's name too | 1-5 |
| `TileItem` | The item's ground actions; Take is 3 | 1-5 |
| `Widget` | Its ops, or for an item slot with none of its own, Use followed by the item's inventory actions | 1-10 |

Conditional options are resolved against the current vars, as the game does. Text is matched loosely: colour tags and
case are ignored, and spaces, `-` and `_` count as the same, so `"chop down"`, `"Chop-Down"` and `"Chop down"` all
match. `interact(int)` takes the number as the menu counts it, from 1.

The rows carry what the game's own rows would. An object's row names its own id and the tile it was placed on, on its
own plane, which isn't `GameObject.getWorldLocation()` (the footprint's centre) for objects three or more tiles across.

`widget.click()` runs the widget's first option, its "Click here to continue", or its old-style button, whichever it
has first. `widget.useOn(target)` selects the widget's item or spell and uses it on an NPC, player, object, ground item
or another widget, in one batch. A spellbook spell is a widget with no item, so the same method casts it. The game only
lets a selection be used on the kinds of target its widget's click mask allows (`WidgetConfig.USE_NPC` and the rest),
and neither does `useOn`.

### Results

Every method returns an `ActionResult` saying what happened, rather than throwing, since the game can change between
the check and the send:

| Result | Meaning |
|---|---|
| `SENT` | The batch went to the hook. The server can still refuse it |
| `NOT_CONNECTED` | The hook isn't connected. Nothing is queued |
| `UNSUPPORTED` | This build's mapping lacks something the action needs |
| `GONE` | The subject isn't there any more: the NPC left, the object changed, the item was taken, the widget closed or its slot holds another item |
| `NO_SUCH_OPTION` | No option with that text or number, or a selection that can't be used on that kind of target |
| `NOT_FOUND` | A service couldn't find what it was asked for: the item isn't held, the spell isn't in the spellbook shown, no dialogue is open |
| `OUT_OF_SCENE` | A walk to a tile outside the loaded area |

`if (npc.interact("Attack").isSent())` reads like a `boolean`. Each failure is also logged at `FINE` on `kraken.api`
with the detail, such as `Goblin (NPC 5) has no "Atack" option; it has Attack, Examine`.

### Services

For common tasks, services find what to act on by id or name. Inject them, or get them from the client
(`client.getInventory()` and so on):

```java
@Inject
private Inventory inventory;

@Inject
private Magic magic;

@Inject
private Prayers prayers;

inventory.interact(ItemID.COINS, "Deposit-All");   // through the bank's inventory panel while the bank is open
inventory.wieldOrWear(ItemID.RUNE_SCIMITAR);
magic.cast(Spell.HIGH_ALCHEMY, ItemID.RUNE_PLATEBODY);
prayers.activate(Prayer.PROTECT_FROM_MELEE);
```

| Service | Methods |
|---|---|
| `Movement` | `walk(worldPoint)` and `walk(localPoint)` within the loaded area; `isRunning()`, `setRunning(boolean)`; `getDestination()` |
| `Dialogue` | `isOpen()`; `canContinue()`, `clickContinue()`; `getOptions()`, `selectOption(text or number)`; `enterAmount`, `enterName`, `enterText`, `selectItem`, each after checking its prompt is the one showing |
| `Inventory` | `contains`, `count`, `isFull`, `isEmpty`, `getItems`, `getSlot(id or name)`; `interact(id, option)`, `use(id, target or other id)`, `drop`, `wield`, `wear`, `wieldOrWear` |
| `Magic` | `cast(spell)` for a spell with no target, `cast(spell, target)`, `cast(spell, itemId)`; `isAvailable(spell)`, `getSpell(spell)`, `getSpellbook()` |
| `Prayers` | `isActive`, `setActive`, `activate`, `deactivate`; `isQuickPrayerActive`, `setQuickPrayer` |
| `Bank` | `isOpen`, `contains`, `count`; `withdraw(id, amount)`, `withdrawAll`, `deposit(id, amount)`, `depositAll`, `depositInventory`, `depositEquipment`, `close` |
| `Equipment` | `isEquipped`, `getItem(slot)`; `remove(slot)`, `interact(slot, option)`, `interact(slot, option, subOption)` |

`Spell` and `Prayer` name each spell and prayer by its interface component. For a spell `Spell` lacks,
`client.getWidget(InterfaceID.MagicSpellbook.X).useOn(target)` does the same as `cast`.

The setters that toggle (run, prayers, quick prayers) only click when the state differs. The game only changes the
state when the server answers, a tick or so later, so each remembers what it sent and takes that as the state until the
var changes or two ticks pass: calling `prayers.activate(...)` every tick doesn't flip the prayer back and forth. They
return `SENT` when the state is already the one asked for.

`bank.withdraw` and `bank.deposit` are the only calls with two steps: an amount the bank's options don't name runs
Withdraw-X (or Deposit-X), and a client thread task answers the amount prompt once the server opens it.

### The Mouse Click

The game sends a mouse click packet before the row of a real click, and so do these methods. Where it lands is the
click policy, `client.getInteractions().setClickPolicy(...)`, which the client's Settings page sets:

| Policy | The click |
|---|---|
| `ON_SCREEN` (the default) | On the subject when it is on screen, otherwise at the static point |
| `STATIC` | Always at the static point, `setStaticClickPoint(...)`, (0, 0) unless set |
| `NONE` | No click, just the row |

A `useOn` clicks the item or spell first and then the target.

### Timing

Each call reads the subject (whether it is still there, its options) on the calling thread and sends one batch at
once, which the game runs at the start of its next frame. Call them from event handlers, which run on the
[client thread](#threads), so the reads and the send see the same moment. Nothing waits for the result: the subject
can still go before the frame runs, and two calls are two batches, run in order. Actions sent this way post no
`MenuOptionClicked`.

The API posts a spawn event for everything already loaded when tracking starts, so a plugin that acts from spawn events
acts on every tree in sight at once. Pick one target on a `GameTick` instead.

## Menu Actions

The [interaction methods](#interacting) are built on these. `client.getActions()` runs a menu option the way a click on
it would, without moving the mouse: the native hook calls
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

The answers don't check that their prompt is showing. `InputDialog.of(client.getVarcIntValue(VarClientID.MESLAYERMODE))`
says which one is: `COUNT`, `NAME`, `STRING`, `OBJ`, or null when none is.

Each returns `true` if it was sent. Supported actions are NPC, object, ground item and player options; walking;
interface options with their sub-options; using items and casting spells on things; old-style buttons; closing
interfaces; and click to continue. Examine, walking by the minimap and RuneLite's own menu entries aren't supported.

Actions aren't queued: while the hook isn't connected, they're dropped and return `false`. The
[Action Tester](plugins.md#action-tester) plugin runs every kind from its settings page.

Two methods on `Client` hop worlds with these actions, as the in-game world switcher does: `openWorldHopper()` clicks
the logout panel's World Switcher button, and `hopToWorld(world)` clicks a world's row in the open switcher (a `World`
from `World.builder().id(330).build()`; only the number counts). The server makes the hop, so the switcher has to be
open first, which takes a tick or so after `openWorldHopper()`, and the server can still refuse, during combat for
example. The client's [World Hopper](client.md#world-hopper) does both steps for you.

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

## Entity Visibility

`client.getEntityVisibility()` hides players, NPCs, projectiles and graphics objects through the native hook, which
applies your **rules** to every entity at the start of every frame. Take rules of your own and close them when done,
which shows again what they hid:

```java
VisibilityRules rules = client.getEntityVisibility().createRules();
rules.setPlayersHidden(true, true);         // other players: models and 2D elements (names, health bars, overhead text)
rules.setNpcsHidden(false, true);           // NPCs: 2D elements only
rules.setProjectilesHidden(true);
rules.setHidden(friend, false, false);      // one player shown anyway, while loaded
rules.setGraphicsObjectsHidden(Set.of(SpotanimID.TOB_NYLOCAS_DEATH_MELEE_STANDARD));
// later
rules.close();
```

Kinds (the local player, other players, NPCs) apply to every actor, in every world, until you decide one apart with
`setHidden`; `reset` puts it back. An entity is hidden if any plugin's rules hide it. Changes reach the game at the end
of the client thread's pass, and an actor that comes into view is drawn for one frame before it is hidden. Rules are
remembered without the hook and sent when it connects. [Entity visibility](developer/entity-visibility.md) has the
details.

## Widget Writes

Four `Widget` setters change the game's component: `setText`, which sets its text the way a script's `cc_settext` does
(the client redraws it), `setDragDeadTime` (how long, in client ticks, the mouse must be held before a drag starts),
`setDragDeadZone` (how far it must move) and `setOnMouseRepeatListener((Object[]) null)`, which clears the component's
mouse-repeat listener (setting one isn't supported). The native hook looks the component up by id at the start of the
next frame and writes then, so a component that has gone is left alone; the snapshot shows the new value at once.
Writes made without the hook are dropped. The game keeps a text until something sets it again, such as the
interface's own scripts or a reload of the interface, so a plugin that shows something in a widget sets it again each
tick:

```java
@Subscribe
public void onGameTick(GameTick event) {
    Widget report = client.getWidget(InterfaceID.Chatbox.REPORTABUSE_TEXT1);
    if (report != null) {
        report.setText(String.valueOf(client.getTickCount()));
    }
}
```

In a `ScriptPreFired` or `ScriptPostFired` handler, `client.getScriptActiveWidget()` is the component the script
engine's `cc_*` ops were acting on at that moment, such as the item slot `ScriptID.INVENTORY_DRAWITEM` just drew, and
`getScriptDotWidget()` the one its `.cc_*` ops were. [Widget writes](developer/widget-writes.md) has the details.

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

* Writes to the game other than highlights, menu actions, menu edits, entity visibility and the drag and mouse-repeat
  widget setters: no camera control, no other widget changes.
* `ScriptCallbackEvent` and sound effect events. `MenuOptionClicked.consume()` exists but can't stop the game's own
  row, which has already run when the event is posted.
* Sub-worlds such as boats, a world number getter, and a chat history getter (use the `ChatMessage` event).
* NPC and player health ratios: there's `isDead()` and whether a health bar is showing.
* Object rotation, so `GameObject` sizes are as if unrotated.

Getters the API can't back are left out rather than stubbed, so if a method exists, it reads something.
