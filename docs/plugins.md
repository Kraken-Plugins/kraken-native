# Plugins

Kraken comes with five plugins. They're all off until you switch them on in the **Plugins** tab, and they double as
examples for [writing your own](writing-plugins.md).

Open a plugin's settings with the cog next to its switch. Kraken has no right-click menu entries of its own, so you
choose what a plugin works on in its settings, by name, id or tile.

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

## NPC Highlight

Outlines the NPCs whose names you list.

| Setting | Default | What it does |
|---|---|---|
| NPC names | | Comma-separated names, such as `Goblin, Guard`. Case doesn't matter, but the name must match in full |
| Colour | Cyan | The outline's colour |
| Outline width | 2 px | 1 to 4 pixels |
| Outline the model | On | Outlines the NPC's model. Needs the native hook |
| Outline the tile | Off | Outlines the tile the NPC stands on. Without the native hook, tiles are always outlined |
| Draw through walls | Off | Keeps the outline visible behind walls and scenery. Needs the native hook |
| Show names | Off | Writes each NPC's name above it |

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

## Tile Markers

Marks the world tiles you list.

| Setting | Default | What it does |
|---|---|---|
| Tiles | | Tiles as `x, y, plane`, separated by semicolons or new lines, such as `3222, 3218, 0; 3223, 3218, 0` |
| Colour | Yellow, translucent | The outline's colour. Its transparency is the fill's |
| Outline width | 2 px | 1 to 4 pixels |
| Fill | On | Fills the tile as well as outlining it |

The **Location** [dev tool](dev-tools.md) shows the tile you're standing on. Entries that can't be read are skipped
and logged in the console. Without the native hook, only tiles on your current plane are drawn.

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
