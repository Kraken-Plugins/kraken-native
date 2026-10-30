# Events

Every event the API posts, in `com.kraken.api.events`. Subscribe with a `@Subscribe` method that takes the event; see
[Events](api.md#events) in the API guide for how they're made and the rules for spawns.

All of them work without the native hook except the three marked **Hook only**. The hook makes the rest arrive the
moment the game raises them instead of on the API's next read, which is up to 20 ms later. For a few, marked
*sharper with the hook*, it also catches changes that undo themselves between two reads, such as an interface closed
and reopened at once.

Events are posted on the client thread. Within one read, they're posted in a fixed order, ending with `GameTick` when a
tick has passed.

## Ticks and the Client

| Event | Posted when | Carries |
|---|---|---|
| `GameTick` | A server tick passes, about every 600 ms. Posted after the tick's other events | |
| `ClientTick` | A client cycle passes, every 20 ms | |
| `GameStateChanged` | The game state changes, such as `LOGIN_SCREEN`, `LOADING` or `LOGGED_IN` | `gameState` |
| `CanvasSizeChanged` | The game's drawing area changes size | |
| `FocusChanged` | The game gains or loses keyboard focus | `focused` |

## NPCs and Players

| Event | Posted when | Carries |
|---|---|---|
| `NpcSpawned`, `NpcDespawned` | An NPC is loaded around you, or leaves | `npc` |
| `PlayerSpawned`, `PlayerDespawned` | A player is loaded around you, or leaves. You count too | `player` |
| `NpcChanged` | An NPC turns into another type in place | `npc`, `old` (its previous definition) |
| `InteractingChanged` | An NPC or player starts, stops or changes what it's interacting with | `source`, `target` |
| `AnimationChanged` | An NPC or player starts, switches or ends an animation | `actor`; read `actor.getAnimation()`, `-1` once it ends |
| `GraphicChanged` | An NPC's or player's spot animations are set, cleared or finish | `actor` |
| `OverheadTextChanged` | Overhead text is set on an NPC or player | `actor`, `overheadText` |
| `HitsplatApplied` | A hitsplat lands | `actor`, `hitsplat` |
| `ActorDeath` | An NPC's or player's health bar empties, once per death | `actor` |

## Objects and Ground Items

| Event | Posted when | Carries |
|---|---|---|
| `GameObjectSpawned`, `GameObjectDespawned` | A game object, such as a tree, rock or table, appears on or leaves its tile | `tile`, `gameObject` |
| `WallObjectSpawned`, `WallObjectDespawned` | A wall, fence or door | `tile`, `wallObject` |
| `DecorativeObjectSpawned`, `DecorativeObjectDespawned` | A wall decoration | `tile`, `decorativeObject` |
| `GroundObjectSpawned`, `GroundObjectDespawned` | A floor decoration | `tile`, `groundObject` |
| `TileObjectSpawned`, `TileObjectDespawned` | Any of the four above. These are interfaces the four implement, so one handler gets them all | `getTileObject()` |
| `ItemSpawned`, `ItemDespawned` | A ground item appears, or is picked up or disappears | `tile`, `item` |
| `ItemQuantityChanged` | A ground item stack's quantity changes | `item`, `tile`, `oldQuantity`, `newQuantity` |

A newly loaded area posts a spawn for each of its objects and ground items, and no despawns for the old area's.

## Stats, Vars and Inventories

| Event | Posted when | Carries |
|---|---|---|
| `StatChanged` | A skill's XP, level or boosted level changes | `skill`, `xp`, `level`, `boostedLevel` |
| `VarbitChanged` | A varp changes. Each varbit on it whose value changed gets an event first, then the varp gets its own | `varpId`, `varbitId` (`-1` for the varp's own event), `value` |
| `VarClientIntChanged`, `VarClientStrChanged` | A client var (varc) changes. *Sharper with the hook* | `index`; read the value with `client.getVarcIntValue` or `getVarcStrValue` |
| `ItemContainerChanged` | An inventory's contents change, or the game starts holding it | `containerId`, `itemContainer` |

## Chat and Interfaces

| Event | Posted when | Carries |
|---|---|---|
| `ChatMessage` | A line is added to the chat | `type`, `name`, `sender`, `message`, `timestamp`, `messageNode` |
| `WidgetLoaded` | An interface opens. *Sharper with the hook* | `groupId` |
| `WidgetClosed` | An interface closes. *Sharper with the hook* | `groupId`, `modalMode`, `unload` |

## The Menu

| Event | Posted when | Carries |
|---|---|---|
| `MenuOpened` | The right-click menu opens | `menuEntries`, `getFirstEntry()` |
| `MenuEntryAdded`, `MenuEntryRemoved` | A row appears in, or leaves, the menu | `menuEntry`, with shortcuts such as `getOption()` and `getTarget()` |
| `MenuOptionClicked` | The game runs a menu entry. Posted after it ran, so it can't be cancelled. *Sharper with the hook* | `menuEntry`, `getMenuOption()`, `getMenuAction()`, `getId()` |

## Projectiles and Spot Animations

| Event | Posted when | Carries |
|---|---|---|
| `ProjectileSpawned` | A projectile is launched | `projectile` |
| `ProjectileMoved` | A projectile heads for a point, or is given a new target | `projectile`, `position`, `z` |
| `GraphicsObjectCreated`, `GraphicsObjectDestroyed` | A spot animation on the ground starts or ends | `graphicsObject` |

## Scripts

The game runs scripts (CS2) for most of its interface work. These events come straight from the native hook.

| Event | Posted when | Carries |
|---|---|---|
| `ScriptPreFired` | **Hook only.** A script is about to run | `scriptId`, `scriptEvent` (null for a script another script called) |
| `ScriptPostFired` | **Hook only.** A script returns | `scriptId` |
| `FakeXpDrop` | **Hook only.** The game shows an XP drop for a skill already at 200M XP | `skill`, `xp` |

During a `ScriptPreFired` or `ScriptPostFired` handler, `client.getIntStack()` and `getObjectStack()` return the
script's stacks as they were at that moment.
