# Plugins

Kraken comes with seven plugins. They're all off until you switch them on in the **Plugins** tab, and they double as
examples for [writing your own](writing-plugins.md). NPC Indicators, Ground Items, Ground Markers and Tile Indicators
are ports of RuneLite's plugins of the same names, with the same settings.

Open a plugin's settings with the cog next to its switch. Kraken can't add entries to the game's right-click menu yet,
so you choose what a plugin works on in its settings, by name, id or tile. Settings that need something Kraken can't do
yet, such as changing the right-click menu, are greyed out; hover over one to see what it needs.

Several plugins draw better with the [native hook](client.md#the-native-hook) connected. With it, the game draws
their outlines itself, around the model, following the camera, and through walls if you ask. Without it, Kraken
outlines tiles in its overlay instead.

## Tick Counter

Counts game ticks while you're logged in, shows the count in a panel in the top-left corner, and logs it to the console
now and then. The count starts again when you log out or hop worlds.

| Setting | Default | What it does |
|---|---|---|
| Show panel | On | Shows the count in a panel over the game |
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
outlined instead. RuneLite's Shift + right-click **Tag** options aren't available yet.

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
| Show import/export/clear options | On | Not supported yet: needs right-click menu changes. Use the buttons below |
| Border width | 2 | How thick outlines are |
| Fill opacity | 50 | How opaque the fill is, 0 to 255 |
| Mark hovered tile | Not set | A key that marks the tile under the mouse, or unmarks it. The game sees the key too, so pick one it doesn't use |
| Import from clipboard | | Adds the marks on the clipboard, as **Export** or RuneLite's Ground Markers export writes them |
| Export to clipboard | | Copies the marks of the area around you |
| Clear loaded markers | | Removes the marks of the area around you |

Marks are drawn within 32 tiles of you, on your plane. RuneLite's Shift + right-click **Mark tile**, **Label** and
**Color** options aren't available yet; labels and colours from an import are shown. Marks don't show in instances
yet.

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
[menu actions](api.md#menu-actions), and needs the native hook for everything except listing the menu.

Every action it runs is sent to the game's servers just as a click would be. Read the disclaimer in the
[README](../README.md) before you use it.

1. Pick an **Action**. The page shows only the settings that action uses.
2. Fill those in. Drop-downs such as **NPC**, **Object** or **Inventory item** list what the game has loaded right now,
   refreshed each time you open them. If several things share a name, the nearest is used.
3. Press **Run**. The console says what was sent, on lines starting `Action Tester:`.

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

When the target is on screen, an action first sends a mouse click at its position, as the game does for a real
click. Turn off **Send mouse click** to send the action alone. **Mouse: click packet only** clicks at the point
in **Click at**, with the left or right button.
