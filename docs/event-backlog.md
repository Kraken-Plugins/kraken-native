# Event backlog

RuneLite events that plugins rely on and that Kraken doesn't post yet, with what each one needs, and a prompt for
adding them. The events Kraken already posts are the classes in `api/src/main/java/com/kraken/api/events` (and
`client/src/main/java/com/kraken/client/events` for the client's own); how they are built is in
`docs/event-hooks-design.md`.

Addresses and offsets are for build 241.3. "Mapped" means the key is in `api/src/main/resources/mappings/241.3.json`.

## Status

| Event | Priority | Mapping work | Status |
|---|---|---|---|
| `AnimationChanged` | Core | None (getter, tracker and the `setSequence` hook) | Done (design §2.10) |
| `ActorDeath` | Core | None (tracker only) | Done (design §2.10) |
| `NpcChanged` | Core | None (tracker only) | Done (design §2.10) |
| `GraphicChanged` | Core | `spotanims.py`: the actor's spot animation slots | Done (design §2.11) |
| `OverheadTextChanged` | Common | `overhead.py`: the text and the actor step counter | Done (design §2.11) |
| `GrandExchangeOfferChanged` | Common | Reverse engineering (raise site mapped) | To do |
| `NpcLootReceived` / `PlayerLootReceived` | Common | None (client-side, needs `ActorDeath`) | To do |
| `AccountHashChanged` / `RuneScapeProfileChanged` | Common | Reverse engineering | To do |
| `WorldChanged` | Common | Small reverse engineering | To do |
| `SoundEffectPlayed` / `AreaSoundEffectPlayed` | Common | Reverse engineering | To do |

Suggested batches, in order: (1) `AnimationChanged`, `ActorDeath`, `NpcChanged` (done); (2) `GraphicChanged`,
`OverheadTextChanged` (done); (3) `GrandExchangeOfferChanged`, `NpcLootReceived`/`PlayerLootReceived`; (4) account
identity, `WorldChanged`; (5) the sound events.

## Core

### AnimationChanged

Done; design §2.10. `Actor.getAnimation()` reads `Actor.anim`, and `AnimationTracker` compares every actor's animation
on the tick's polls and after a `setSequence`, `npcInfoDecode` or `playerInfoDecode` raise, and the animating actors'
on every poll. The open questions, answered:

- The client ends animations outside `setSequence`: the per-cycle actor step (`0x70d80`) clears a finished sequence,
  and leaving the scene or moving can cancel one. So only the comparison sees ends.
- Players don't go through `setSequence` but a second setter, `0x71470` (it also swaps the id through a table on the
  Client). Its calls with server updates run inside `playerInfoDecode`, already hooked, so it isn't mapped; a start the
  local-player packet or a script makes between ticks is seen on the next tick's poll.
- Restarting the animation an actor is playing posts nothing: the client doesn't write the sequence again.
- Reading every actor every poll would cost about ten reads per actor; reading only the animating ones between ticks
  keeps ends prompt for less.

### ActorDeath

Done; design §2.10. `DeathTracker` posts when `Actor.isDead()` turns true, with a strict read so a failed one can't
fake a second death. An actor that comes into view already dead posts one; one that despawns while dead posts nothing
more. The tracker posts when the empty bar starts showing, not as the update arrives.

### NpcChanged

Done; design §2.10. A transform rewrites `Npc.type` in place (the NPC update decoder's mask bit `0x2`), so the NPC
keeps its address, spawn cycle and handle, and `ActorTracker` posts no despawn or spawn. `NpcChangeTracker` compares
every NPC's base type id and posts the old definition; a multi-NPC's varbit transform posts nothing.

### GraphicChanged

Done; design §2.11. The update masks and the spot animation packets hand each one to `functions.setSpotAnim`
(`0x9ec30`, seven arguments, so not hooked), which keeps them in a vector map on the actor sorted by slot
(`Actor.spotAnims`), each slot pointing at a new spot animation (`ActorSpotAnim`: id, height, start cycle, frame). The
draw's SpotAnimType loads anchor the layout (ANCHORED). `GraphicTracker` compares each actor's slots, ids and start
cycles; the answers:

- The server can send several: the actor holds one per slot (a byte), and a new one in a slot replaces it.
- The client ends them itself (the per-cycle actor step erases one whose sequence finished), so the actors showing one
  are read every poll, and an end posts too.
- `getGraphic()`, `getGraphicHeight()` and `getSpotAnimFrame()` read the lowest slot; `ActorSpotAnim` has no frame
  cycle (not mapped).

## Common

### OverheadTextChanged

Done; design §2.11. The text is an eastl string just before the timer (`Actor.overheadText`, `0x2d8`), written by the
NPC update's say block, the player update's say and public chat blocks, and Lua's `npcSay` and `playerSay` (which
anchor it, PROVEN with the countdown). The client counts the timer down once per actor step and empties the text at 0,
and counts the steps (`Client.actorSteps`), so `OverheadTextTracker` tells the same line said again from a timer the
countdown can't explain. A line timing out posts nothing.

### GrandExchangeOfferChanged

- **RuneLite:** `GrandExchangeOfferChanged { GrandExchangeOffer offer; int slot }`, with
  `Client.getGrandExchangeOffers()` (8 slots: state, item id, price, total quantity, quantity sold, spent).
- **Have:** the `ON_ACTIVE_OFFERS_TRANSMIT` raise site (the generic deferred transmit wrapper `0x520b0`), which the DLL
  can already hook.
- **Needs reverse engineering:** the packet case that raises it and writes a slot, and the offers array it writes.
  On login the server sends every slot, so decide what the first read posts.

### NpcLootReceived / PlayerLootReceived

- **RuneLite:** client-side events (`net.runelite.client.events`): `NpcLootReceived { NPC npc; Collection<ItemStack>
  items }` and `PlayerLootReceived { Player player; Collection<ItemStack> items }`, built by RuneLite's `LootManager`
  from deaths, despawns and the items that appear where the actor died.
- **Have:** `NpcDespawned`, `PlayerDespawned`, `ItemSpawned`, `ItemQuantityChanged`.
- **Needs:** `ActorDeath` first, then a loot manager in the client module that attributes the items spawned at the
  death tile (or under a large NPC) on the following ticks. No mapping.

### Account identity: AccountHashChanged and RuneScapeProfileChanged

- **RuneLite:** `Client.getAccountHash()` and `AccountHashChanged`; on the client side, `RuneScapeProfileChanged` and
  per-account config (`ConfigManager`'s RuneScape profiles).
- **Have:** nothing confirmed. `Client.loginToken` (`0x3318`) is compared with -1 in `isLoggedIn`; its meaning is
  unknown.
- **Needs reverse engineering:** where the client keeps the account's id or hash after login. Then profiles in the
  client's `ConfigManager`, so plugins can save data per account.

### WorldChanged

- **RuneLite:** `WorldChanged` (no fields), with `Client.getWorld()`.
- **Have:** `Client.worldFlags` (`0x30a4`, the current world's type bits) and `Client.worlds` (the world list).
- **Needs reverse engineering:** the current world's number, likely written beside `worldFlags` on login and on a hop.
  `GameStateChanged(HOPPING)` already covers most uses.

### SoundEffectPlayed / AreaSoundEffectPlayed

- **RuneLite:** `SoundEffectPlayed { Actor source; int soundId; int delay }` and `AreaSoundEffectPlayed { Actor
  source; int soundId; int sceneX; int sceneY; int range; int delay }`.
- **Needs reverse engineering:** the sound effect packet handlers and the sounds an animation's frames play. Check
  whether the functions to hook take every argument in registers; if not, look for a queue the client keeps that a
  poll can read.

## Not planned for now

- `PlayerChanged` (other players' appearance and gear), the friends and clan events (their raises are mapped, the data
  mostly isn't), `CommandExecuted` (`::` commands), `ResizeableChanged`, `WorldViewLoaded`/`WorldViewUnloaded`
  (Sailing boats), and `BeforeRender`/`PostClientTick` (Kraken's frame listener covers them).
- Events that exist so plugins can change the game don't fit a read-only API: `MenuShouldLeftClick` and `PostMenuSort`
  (menu entry swapping), and `ChatboxInput`/`ChatInput` (intercepting what the player types).

## Prompt

Replace `<EVENTS>` with a batch from the table above, e.g. `AnimationChanged, ActorDeath and NpcChanged`.

```text
Map <EVENTS> in the updater (where they need new mapping keys) and add them to the API, so the client and plugins
using the EventBus can subscribe to them.

Read first: CLAUDE.md; docs/event-backlog.md (each event's entry: what is mapped, what isn't, open questions);
docs/event-hooks-design.md (§2.5 function hooks, §2.7-§2.9 the most recent events, §4.3 how raises trigger trackers,
§7 live checks). Use the varc events as the template for the whole change: updater/src/native_updater/varcs.py,
spec/64-varcs.yaml, tests/test_varcs.py, api/.../internal/event/VarcTracker.java, internal/VarcEventsTest.java, and
the matching entries in the docs.

For each event:
1. Match RuneLite's event: class name, fields, types and when it fires (check RuneLite's source). Add the getters the
   event needs under RuneLite's names on the class they belong to. Note anything we can't match and why.
2. Reverse engineer what isn't mapped yet against the 241.3 build (program.db through ~/.kraken-native/scripts/rq.py,
   the updater's Provenance facts, analysis.json bindings). Explain what you find at a beginner level; I'm new to x64
   reverse engineering.
3. Updater (only for new keys): a recipe module and spec YAML, registered in extractors.py, with a unit test over a
   FakeModel miniature; lint the spec. Regenerate the 241.3 mapping into a temp folder (don't overwrite the cache's
   analysis.json), confirm only the new keys changed, and copy it into api/src/main/resources/mappings/. Add the keys
   and a source note to updater/corpus/241.3.golden.json. Run every updater test including the golden one, and report
   the golden test as skipped if it skipped.
4. API: the event class in com.kraken.api.events (Lombok, RuneLite's shape) and a Tracker in internal.event, wired
   into EventSource (update its Javadoc's posting order too). The tracker must work without the hook by comparing
   reads every poll; hook raises only decide when to read. Reads whose failure could look like a change must be
   strict (fail as a whole, keep the baseline). Extend GameFixture and add tests in the package of the class under
   test.
5. Hooks: hook a function only if every argument is in RCX, RDX, R8 or R9 (the generic detour can't forward stack
   arguments). Add its name to NativeEvent and to the client's NativeEvents.FUNCTIONS, and check the DLL's 64-slot
   budget. Don't change the DLL unless there is no other way; if there isn't, tell me why before doing it.
6. Client: an option for the new events in the Event log dev tool (EventLogTool).
7. Docs: a section in docs/event-hooks-design.md (addresses, what the tracker does, deliberate differences from
   RuneLite, a §4.3 table row, §7 live checks), CLAUDE.md where it lists spec files and events, and mark the events
   done in docs/event-backlog.md.
8. Verify: ./gradlew :api:test :client:test -x buildNativeHook, plus every updater test.

Follow CLAUDE.md's conventions: no RuneLite mentions in comments, Javadoc or test messages; multi-line Javadoc with
@param and @return; no banner comments; 120 columns. Keep each file's line endings (git ls-files --eol). Run the
updater's Python from Windows with the temp venv, never letting Windows uv touch updater/.venv. If the game isn't
running, say the work isn't live-tested and list the live checks. Don't commit; end with a conventional commit
message of at most 50 characters.
```
