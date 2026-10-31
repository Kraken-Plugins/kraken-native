# Plugins

Kraken comes with eleven plugins. They're all off until you switch them on in the **Plugins** tab, and they double
as examples for [writing your own](writing-plugins.md). NPC Indicators, Ground Items, Ground Markers, Tile Indicators,
Menu Entry Swapper, Key Remapping, Entity Hider and Anti Drag are ports of RuneLite's plugins of the same names, with
the same settings.

Open a plugin's settings with the cog next to its switch. NPC Indicators, Ground Markers and Menu Entry Swapper also
add options to the right-click menu while you hold Shift; most others choose what they work on in their settings, by
name, id or tile. Settings that need something Kraken can't do yet are greyed out; hover over one to see what it
needs.

Several plugins draw better with the [native hook](client.md#the-native-hook) connected. With it, the game draws
their outlines itself, around the model, following the camera, and through walls if you ask. Without it, Kraken
outlines tiles in its overlay instead.

## Tick Counter

Counts game ticks while you're logged in, shows the count on the chatbox's Report button in place of the word "Report",
and logs it to the console now and then. The count starts again when you log out or hop worlds, and the button says
"Report" again when you switch the plugin off. It needs the [native hook](client.md#the-native-hook) to change the
button.

| Setting | Default | What it does |
|---|---|---|
| Log every | 1 min | How often to log the count to the console. 0 never logs it |

## NPC Indicators

Highlights the NPCs whose names you list, and can show when and where they respawn.

| Setting | Default | What it does |
|---|---|---|
| NPCs to highlight | | Comma-separated names, such as `Goblin, Guard*`. Case doesn't matter; `*` matches anything |
| Draw names above NPC | Off | Writes each highlighted NPC's name above it |
| Draw names on minimap | Off | Not supported yet: needs minimap positions |
| Highlight menu style | None | Not supported yet: needs right-click menu changes |
| Ignore dead NPCs | On | Stops highlighting NPCs as they die |
| Dead NPC menu color | | Not supported yet: needs right-click menu changes |
| Show respawn timer | Off | After a highlighted NPC dies in view, marks where it will respawn and counts down to it, once its respawn time has been seen |
| Ignore pets | On | Leaves followers, such as pets, out |

**Render style:**

| Setting | Default | What it does |
|---|---|---|
| Highlight hull | On | Outlines and fills the NPC's model. The game draws it, so it needs the native hook |
| Highlight tile | Off | Outlines the tiles the NPC stands on |
| Highlight true tile | Off | Outlines the tiles the server has the NPC on |
| Highlight south west tile | Off | Outlines the NPC's south-west tile |
| Highlight south west true tile | Off | Outlines the server's south-west tile of the NPC |
| Highlight outline | Off | Outlines the NPC's model. The game draws it, so it needs the native hook |
| Highlight color | Cyan | The outline and name colour |
| Fill color | Cyan, faint | The fill colour |
| Border width | 2 | How thick outlines are |
| Outline feather | 0 | Not supported yet: the game's outline has no feather |

The game draws hulls and outlines through walls. Without the native hook, NPCs with those styles get their tiles
outlined instead.

Hold Shift and right-click an NPC for these options, just above its **Examine**:

| Option | What it does |
|---|---|
| **Tag** / **Un-tag** | Highlights this one NPC, or stops. Tags aren't saved: they go when the plugin is switched off or Kraken closes |
| **Tag-All** / **Un-tag-All** | Adds the NPC's name to **NPCs to highlight**, or takes it out. Not shown for a name a `*` pattern matches |
| **Tag color** | On a highlighted NPC: a colour for every NPC of its kind, from those in use nearby, **Pick color** (opens a colour picker) or **Reset** |
| **Tag style** | On a highlighted NPC: one style for every NPC of its kind (hull, tile, true tile, south-west tile, south-west true tile or outline) in place of the render style settings, or **Reset** |

The options need the native hook.

## Ground Items

Writes the names, quantities and values of items on the ground, coloured by value or by your lists.

| Setting | Default | What it does |
|---|---|---|
| Highlighted items | | Comma-separated names to highlight. `*` matches anything; `Coins>1000` or `Bones<5` match by quantity |
| Hidden items | `Vial, Ashes, Coins, Bones, Bucket, Jug, Seaweed` | Comma-separated names to hide, the same way |
| Show highlighted items only | Off | Hides everything not highlighted |
| Do not hide untradeables | On | Keeps untradeable items visible even when listed as hidden |
| Highlight tiles | Off | Outlines the tiles of items that are shown |
| Price display mode | Both | Which value to write after an item. Kraken only knows high alchemy values, so `GE` writes nothing |
| Item highlight mode | Both | Where to highlight items; only the text over the game works yet |
| Highlight value calculation | Highest | Which value picks the colour tier. Kraken only knows high alchemy values |
| Hide under value | 0 | Hides items worth less than this |
| Default, highlighted, hidden items | White, purple, grey | Text colours |
| Low, medium, high, insane value items and prices | 20K, 100K, 1M, 10M | The colour tiers and the values that start them |
| Double-tap delay | 250 ms | Double-tapping the hotkey within this hides every item until you press it again; 0 turns this off |
| Text outline | Off | Outlines the text instead of shadowing it |
| Hotkey | Alt | Hold it to show every item, hidden ones in the hidden colour |
| Show menu item quantities, Recolor menu hidden items, Deprioritize menu hidden items, Menu highlight mode, Collapse ground item menu | | Not supported yet: need right-click menu changes |
| Notify for highlighted drops, Notify tier | | Not supported yet: need notifications |
| Ownership filter | | Not supported yet: needs to know whose items are whose |
| Despawn timer | | Not supported yet: needs to know when items appear and disappear |
| Highlighted item lootbeams, Lootbeam tier, Lootbeam style | | Not supported yet: need models drawn in the game |

The game sees the hotkey too, so holding Alt also does whatever Alt does in the game. Clicking an item's name while
holding the hotkey to add it to a list isn't available yet.

## Ground Markers

Marks tiles. Marks are kept per region, so they come back whenever you're nearby, and move between players or
RuneLite through the clipboard.

| Setting | Default | What it does |
|---|---|---|
| Tile color | Yellow | The colour new marks get |
| Draw tiles on minimap | Off | Not supported yet: needs minimap positions |
| Show import/export/clear options | On | Not supported yet: the world map orb's menu isn't changed. Use the buttons below |
| Border width | 2 | How thick outlines are |
| Fill opacity | 50 | How opaque the fill is, 0 to 255 |
| Mark hovered tile | Not set | A key that marks the tile under the mouse, or unmarks it. The game sees the key too, so pick one it doesn't use |
| Import from clipboard | | Adds the marks on the clipboard, as **Export** or RuneLite's Ground Markers export writes them |
| Export to clipboard | | Copies the marks of the area around you |
| Clear loaded markers | | Removes the marks of the area around you |

Hold Shift and right-click a tile for these options, just below **Walk here**:

| Option | What it does |
|---|---|
| **Mark Tile** / **Unmark Tile** | Marks the tile in the tile colour, or takes its mark off |
| **Label Tile** | On a marked tile: asks for text to write on it. Leave it empty to take the label off |
| **Color Tile** | On a marked tile: a colour in use nearby, **Pick** (opens a colour picker), or **Reset all**, which puts every mark in the region back to the tile colour after asking |

The options need the native hook. The label and colour are asked for in small windows over the game, as Kraken can't
type into the chatbox. Marks are drawn within 32 tiles of you, on your plane. Marks don't show in instances yet.

## Menu Entry Swapper

Changes which option a left click, or a Shift-click, runs: Bank instead of Talk-to on bankers, Last-destination on
fairy rings, Withdraw-5 on bank items while Shift is held, and so on. It needs the [native hook](client.md#the-native-hook),
which changes the menu as the game builds it, so the swapped option is the left click from the moment it shows.

The settings are RuneLite's, in the same sections: **Item swaps**, **NPC swaps**, **Object swaps**, **UI swaps** and
**Ground item swaps**. Each is a switch or a choice of which option to bring up; the Shift-click ones (bank withdraw and
deposit, shop buy and sell, GE abort, Stairs shift-click, Shift-click Walk here) apply while Shift is held.

Pick your own left-click and Shift-click options the way RuneLite does: hold Shift and right-click an item, an NPC, an
object or a worn item, and open the **Swap left-click** or **Swap shift-click** sub-menu. Those choices are stored under
RuneLite's keys, so a RuneLite settings export works here. Two differences: a swap to a sub-option (the options under
an item's or object's sub-menu) isn't offered or applied, and **Shift-click Walk here** applies to every object or NPC,
including one with a Shift-click swap of its own. The game's own Attack setting is left to the game, as in RuneLite.

## Key Remapping

Lets you move the camera with W, A, S and D, and press number keys for the F-keys, while the chat is locked: until you
press Enter, letters you type go nowhere and the remapped keys do their new jobs; Enter unlocks the chat for typing,
and Enter or Escape locks it again. It needs the [native hook](client.md#the-native-hook), which changes the keys
before the game sees them.

| Setting | Default | What it does |
|---|---|---|
| Remap camera | On | W, A, S and D (or the keys you set) become the arrow keys while the chat is locked |
| Camera up, down, left, right key | W, S, A, D | The keys that stand in for the arrows |
| Remap F-keys | Off | The number keys (or the keys you set) become F1 to F12, and the ESC key setting Escape, while the chat is locked and no dialog is taking numbers |
| F1 to F12, ESC | 1 to 0, -, =, Esc | The keys that stand in for each |
| Space | Space | The key that stands in for Space while a dialog is open |
| Control | Ctrl | The key that stands in for Control |

Unlike RuneLite, the chat input keeps its usual look while locked ("Press Enter to Chat..." isn't shown), and the text
you typed stays in the input when you press Escape. The remapping also stays on while an interface's own text field has
the keyboard, which Kraken can't yet tell apart from the chat; press Enter to type into it.

## Entity Hider

Hides other players, NPCs, projectiles, or yourself: their models, their 2D elements (names, health bars and overhead
text), or both. It needs the [native hook](client.md#the-native-hook), which hides them as the game draws each frame.

| Setting | Default | What it does |
|---|---|---|
| Hide others | On | Hides other players' models |
| Hide others' 2D | On | Hides other players' names, health bars and overhead text |
| Hide party members | Off | Not supported yet: needs a party |
| Hide friends | Off | Hides friends too; off keeps them |
| Hide friends chat members, Hide clan chat members, Hide ignores | Off | The same for friends chat members, clan members and ignored players |
| Hide local player, Hide local player 2D | Off | Hides your own model, or your own 2D elements |
| Hide NPCs, Hide NPCs 2D | Off | Hides NPCs' models, or their 2D elements |
| Hide boats | Off | Not supported yet: needs other players' boats |
| Hide others' pets | Off | Hides other players' followers |
| Hide attackers | Off | Hides the NPCs and players attacking you |
| Hide projectiles | Off | Hides every projectile |
| Hide dead NPCs | Off | Hides NPCs as they die, and the Nylocas' death splashes in the Theatre of Blood |
| Hide thralls | Off | Hides thralls |
| Hide others' random events | Off | Hides random event NPCs |

A player or NPC that comes into view shows for one frame before it is hidden. Who counts as a friend, an attacker or
a dying NPC is worked out as they appear and change, and again every game tick, rather than every frame.

## Anti Drag

Makes an item wait before it can be dragged, so a quick click never drags it by mistake: in the inventory, the bank,
the deposit box, group storage, the Chambers of Xeric storage, the seed vault and the rune pouch. It needs the
[native hook](client.md#the-native-hook), which changes the item slots in the game.

| Setting | Default | What it does |
|---|---|---|
| Drag delay | 30 | How long to hold an item before it drags, in client ticks of 20 ms (30 is one game tick) |
| On shift only | On | Only applies the delay while you hold Shift |
| Disable on control pressed | Off | Lifts the delay while you hold Control (with On shift only off) |

Shift and Control are checked once a client tick, so a very quick tap can be missed.

## Tile Indicators

Marks the tile you're walking to, the tile under the mouse and the tile you stand on.

| Setting | Default | What it does |
|---|---|---|
| Highlight destination tile | On | Marks the tile you're walking to, the one the game flags |
| Highlight hovered tile | Off | Marks the tile under the mouse |
| Highlight true tile | Off | Marks the tile the server has you on |
| Highlight color, Fill color, Border width | Outline grey (destination), clear (hovered) or cyan (true tile); fill faint black; width 2 | Each mark's look, set for each mark |

Nothing is marked during cutscenes.

## Object Markers

Outlines objects, such as trees, rocks, bank booths and doors, by name or id. It picks up every matching object in the
loaded area, and new ones as they appear.

| Setting | Default | What it does |
|---|---|---|
| Objects | | Comma-separated names or ids, such as `Bank booth, Oak tree, 10355`. Numbers are ids, anything else a full name. Case doesn't matter |
| Colour | Yellow | The outline's colour |
| Outline width | 2 px | 1 to 4 pixels |
| Outline the model | On | Outlines the object's model. Needs the native hook |
| Outline the tiles | Off | Outlines the tiles the object covers. Without the native hook, tiles are always outlined |
| Draw through walls | Off | Keeps the outline visible behind other scenery. Needs the native hook |
| Show names | Off | Writes each object's name on it |

To find an object's name or id, turn on the **Game objects** [dev tool](dev-tools.md) and look at it.

## Action Tester

Runs one menu action, or one of the game's dialog answers, from its settings page: attack an NPC, open a door, eat an
item, cast a spell, walk to a tile, enter an amount, and so on. It's a test bench for the API's
[interaction methods](api.md#interacting) and [menu actions](api.md#menu-actions), and needs the native hook for
everything except listing the menu.

Every action it runs is sent to the game's servers just as a click would be. Read the disclaimer in the
[README](../README.md) before you use it.

1. Pick an **Action**. The page shows only the settings that action uses.
2. Fill those in. Drop-downs such as **NPC**, **Object** or **Inventory item** list what the game has loaded right now,
   refreshed each time you open them. If several things share a name, the nearest is used.
3. Press **Run**. The console says what was run and its result (`SENT`, or why nothing was sent, such as
   `NO_SUCH_OPTION` with the options the subject has), on lines starting `Action Tester:`.

Turn on **Repeat** to run the action again every few ticks (**Repeat every**, 5 ticks by default) until you turn it
off.

The actions come in groups:

| Group | Actions |
|---|---|
| Menu | List the entries of the current menu; run one of them |
| NPC, Player, Object, Ground item, Inventory | Pick an option (such as Attack, Open or Eat); use an item on it; cast a spell on it |
| Magic, Prayer, Combat | Cast a spell; toggle a prayer or quick-prayers; toggle the special attack |
| Widget | An interface button's option; an old-style button; click to continue; close the interface |
| Dialog | Enter an amount, a name or text; choose an item |
| Walk | Walk to a tile near you or to a world tile; toggle run |
| Mouse | Send only the mouse click |

An action first sends a mouse click as the game does for a real click, where the **Mouse click** setting on the
[Settings](client.md#settings) page says. **Mouse: click packet only** clicks at the point in **Click at**, with the
left or right button.
