package com.kraken.client.plugins.actiontester;

import com.kraken.api.Client;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.definition.ObjectComposition;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.scene.Scene;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.widgets.Widget;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * What the Action Tester's settings name, found among what the client has loaded, and the lists its settings page
 * suggests: the names of what is loaded, a subject's options, the components an action fits, the spellbook's spells,
 * the prayer book's prayers and the menu's entries. Names match whatever their case; of several things with a name,
 * the nearest to the player is used. Client thread only.
 */
final class Subjects {

    /**
     * The inventory component the item actions look in.
     */
    static final int INVENTORY = InterfaceID.Inventory.ITEMS;

    /**
     * The button type of a pause ("Click here to continue") component.
     */
    static final int PAUSE_BUTTON = 6;

    /**
     * The actions of the old-style button types the button action clicks: a plain button, a close button, a toggle
     * and a set. Type 2 selects a target (the Use actions) and type 6 is the pause button (its own action).
     */
    static final Map<Integer, MenuAction> BUTTONS = Map.of(1, MenuAction.WIDGET_TYPE_1,
            3, MenuAction.WIDGET_CLOSE, 4, MenuAction.WIDGET_TYPE_4, 5, MenuAction.WIDGET_TYPE_5);

    /**
     * The highest component op the menu runs as {@link MenuAction#CC_OP}; higher ones are
     * {@link MenuAction#CC_OP_LOW_PRIORITY}.
     */
    private static final int HIGH_PRIORITY_OPS = 5;

    /**
     * The most entries a suggestion list holds.
     */
    private static final int MAX_SUGGESTIONS = 100;

    /**
     * The client's text markup, such as {@code <col=ffff00>}.
     */
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");

    private final Client client;

    /**
     * Creates the finder.
     *
     * @param client the client
     */
    Subjects(Client client) {
        this.client = client;
    }

    /**
     * The nearest NPC with a name.
     *
     * @param name the name
     * @return the NPC, if one is loaded
     */
    Optional<NPC> npc(String name) {
        return nearest(client.getNpcs(), name, Actor::getName, Actor::getWorldLocation);
    }

    /**
     * The nearest player with a name.
     *
     * @param name the name
     * @return the player, if one is loaded
     */
    Optional<Player> player(String name) {
        return nearest(client.getPlayers(), name, Actor::getName, Actor::getWorldLocation);
    }

    /**
     * The nearest object (loc) with a name.
     *
     * @param name the name
     * @return the object, if one is loaded
     */
    Optional<TileObject> object(String name) {
        Scene scene = client.getScene();
        return scene == null ? Optional.empty() : nearest(scene.getTileObjects(), name, this::objectName,
                TileObject::getWorldLocation);
    }

    /**
     * The nearest ground item with a name.
     *
     * @param name the name
     * @return the item, if one is loaded
     */
    Optional<TileItem> groundItem(String name) {
        Scene scene = client.getScene();
        return scene == null ? Optional.empty() : nearest(scene.getGroundItems(), name, this::itemName,
                item -> item.getTile().getWorldLocation());
    }

    /**
     * The first inventory slot holding an item with a name.
     *
     * @param name the item's name
     * @return the slot's widget, if the item is there
     */
    Optional<Widget> inventorySlot(String name) {
        String wanted = name.trim();
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return inventorySlots().stream().filter(slot -> wanted.equalsIgnoreCase(itemName(slot.getItemId())))
                .findFirst();
    }

    /**
     * The widget of a component given as {packed id, slot}.
     *
     * @param component the component, as {@link #parseComponent} reads it
     * @return the widget, or its child at the slot; null if it isn't loaded
     */
    Widget widget(int[] component) {
        Widget widget = client.getWidget(component[0]);
        if (widget != null && component[1] >= 0) {
            widget = widget.getChild(component[1]);
        }
        return widget;
    }

    /**
     * The first pause button showing among the open interfaces.
     *
     * @return the button's widget, or null if none shows
     */
    Widget firstPauseButton() {
        List<Widget> found = shownComponents(widget -> widget.getEvent() == PAUSE_BUTTON, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The menu's entries in the order the menu shows them: the top (left-click) row first.
     *
     * @return the entries; empty if the menu can't be read
     */
    MenuEntry[] menuRows() {
        MenuEntry[] rows = client.getMenuEntries();
        Collections.reverse(Arrays.asList(rows));
        return rows;
    }

    /**
     * The menu entry a setting names: a row number (0 is the top row), or the first row whose text is the setting's.
     *
     * @param rows the menu's rows, top first
     * @param text the setting
     * @return the entry, or null if the menu has none such
     */
    static MenuEntry menuEntry(MenuEntry[] rows, String text) {
        String wanted = text.trim();
        int row = number(wanted);
        if (row >= 0) {
            return row < rows.length ? rows[row] : null;
        }
        for (MenuEntry entry : rows) {
            if (menuText(entry).equalsIgnoreCase(wanted)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * A menu entry's text as the menu shows it, without markup.
     *
     * @param entry the entry
     * @return the option and the target
     */
    static String menuText(MenuEntry entry) {
        return plain(entry.getOption() + " " + entry.getTarget());
    }

    /**
     * An NPC's options, from its composition (as transformed, when it is).
     *
     * @param npc the NPC
     * @return the options by op, 1-based in the array's order; null if unknown
     */
    String[] npcActions(NPC npc) {
        NPCComposition composition = npc.getTransformedComposition();
        if (composition == null) {
            composition = npc.getComposition();
        }
        return composition == null ? null : composition.getActions();
    }

    /**
     * An object's options, from its composition.
     *
     * @param object the object
     * @return the options by op, 1-based in the array's order; null if unknown
     */
    String[] objectActions(TileObject object) {
        ObjectComposition composition = client.getObjectDefinition(object.getId());
        return composition == null ? null : composition.getActions();
    }

    /**
     * A ground item's options: the item's ground actions (Take is the third).
     *
     * @param item the ground item
     * @return the options by op, 1-based in the array's order; null if the item isn't known
     */
    String[] groundActions(TileItem item) {
        ItemComposition composition = client.getItemDefinition(item.getId());
        return composition == null ? null : composition.getGroundActions();
    }

    /**
     * An inventory slot's options: the slot's own ops when it has them, else Use followed by the item's inventory
     * actions.
     *
     * @param slot the slot's widget
     * @return the options by op, 1-based in the array's order; null if unknown
     */
    String[] itemActions(Widget slot) {
        String[] own = slot.getActions();
        if (own != null) {
            return own;
        }
        ItemComposition composition = client.getItemDefinition(slot.getItemId());
        String[] inventory = composition == null ? null : composition.getInventoryActions();
        if (inventory == null) {
            return null;
        }
        String[] ops = new String[inventory.length + 1];
        ops[0] = "Use";
        System.arraycopy(inventory, 0, ops, 1, inventory.length);
        return ops;
    }

    /**
     * The name of the item with an id.
     *
     * @param itemId the item's id
     * @return its name, or null if it isn't known
     */
    String itemName(int itemId) {
        ItemComposition composition = client.getItemDefinition(itemId);
        return composition == null ? null : composition.getName();
    }

    /**
     * The names of the NPCs loaded now, nearest first.
     *
     * @return the names, each once
     */
    List<String> npcNames() {
        return names(client.getNpcs(), Actor::getName, Actor::getWorldLocation);
    }

    /**
     * The names of the players loaded now other than the local player, nearest first.
     *
     * @return the names, each once
     */
    List<String> playerNames() {
        Player local = client.getLocalPlayer();
        String self = local == null ? null : local.getName();
        List<String> names = new ArrayList<>(names(client.getPlayers(), Actor::getName, Actor::getWorldLocation));
        names.removeIf(name -> name.equalsIgnoreCase(self));
        return names;
    }

    /**
     * The names of the objects loaded now that have an option, nearest first.
     *
     * @return the names, each once
     */
    List<String> objectNames() {
        Scene scene = client.getScene();
        if (scene == null) {
            return List.of();
        }
        List<TileObject> withOptions = scene.getTileObjects().stream()
                .filter(object -> hasAny(objectActions(object))).toList();
        return names(withOptions, this::objectName, TileObject::getWorldLocation);
    }

    /**
     * The names of the ground items loaded now, nearest first.
     *
     * @return the names, each once
     */
    List<String> groundItemNames() {
        Scene scene = client.getScene();
        return scene == null ? List.of() : names(scene.getGroundItems(), this::itemName,
                item -> item.getTile().getWorldLocation());
    }

    /**
     * The names of the inventory's items, in slot order.
     *
     * @return the names, each once
     */
    List<String> inventoryNames() {
        return inventorySlots().stream().map(slot -> itemName(slot.getItemId())).filter(Subjects::named).distinct()
                .toList();
    }

    /**
     * The spells of the spellbook the client shows, by the names they show (its components that aren't hidden and
     * have a name); every {@link InterfaceID.MagicSpellbook} constant's name, as words, if none shows one.
     *
     * @return the spells' names, e.g. Varrock Teleport
     */
    List<String> spellNames() {
        return shownNames(InterfaceID.MagicSpellbook.class, constant -> true);
    }

    /**
     * The prayer book's prayers, by the names they show; Prayer1 to Prayer30 (the {@link InterfaceID.Prayerbook}
     * constants) if none shows one.
     *
     * @return the prayers' names, e.g. Protect from Melee
     */
    List<String> prayerNames() {
        return shownNames(InterfaceID.Prayerbook.class, Subjects::isPrayer);
    }

    /**
     * The spellbook component a spell setting names: as {@link #constantComponent} reads it for the spellbook, or the
     * component showing the name, one in the current spellbook first.
     *
     * @param text the setting
     * @return the packed component id, or -1 if the text names no spell
     */
    int spell(String text) {
        return named(InterfaceID.MagicSpellbook.class, constant -> true, text);
    }

    /**
     * The prayer book component a prayer setting names: as {@link #constantComponent} reads it for the prayer book
     * (PRAYER19, or Prayer 19), or the prayer showing the name.
     *
     * @param text the setting
     * @return the packed component id, or -1 if the text names no prayer
     */
    int prayer(String text) {
        return named(InterfaceID.Prayerbook.class, Subjects::isPrayer, text);
    }

    /**
     * A minimap orb's button: the first of its components (the orbs have one interface beside the minimap and one
     * without it) that is loaded and showing, else the first loaded.
     *
     * @param ids the button's components, e.g. {@link InterfaceID.Orbs#RUNBUTTON} and
     *            {@link InterfaceID.OrbsNomap#RUNBUTTON}
     * @return the button's widget, or null if none is loaded
     */
    Widget orb(int... ids) {
        Widget loaded = null;
        for (int id : ids) {
            Widget widget = client.getWidget(id);
            if (widget != null && !widget.isHidden()) {
                return widget;
            }
            if (loaded == null) {
                loaded = widget;
            }
        }
        return loaded;
    }

    /**
     * The components showing in the open interfaces that an action fits: those with options for a widget option,
     * old-style buttons for the button action, pause buttons for click to continue. Each is written as
     * group:child or group:child:slot, then what it shows.
     *
     * @param test the action
     * @return the components, in interface order; empty for an action that takes none
     */
    List<String> components(ActionTest test) {
        Predicate<Widget> fits = switch (test) {
            case WIDGET_OPTION -> widget -> hasAny(widget.getActions());
            case WIDGET_BUTTON -> widget -> BUTTONS.containsKey(widget.getEvent());
            case PAUSE_BUTTON -> widget -> widget.getEvent() == PAUSE_BUTTON;
            default -> null;
        };
        if (fits == null) {
            return List.of();
        }
        return shownComponents(fits, MAX_SUGGESTIONS).stream().map(Subjects::componentText).toList();
    }

    /**
     * The menu's entries, top row first.
     *
     * @return their texts, each once
     */
    List<String> menuEntryTexts() {
        return Arrays.stream(menuRows()).map(Subjects::menuText).filter(text -> !text.isEmpty()).distinct()
                .toList();
    }

    /**
     * The options of the subject an option action would act on now, with each op's sub-options after a dot.
     *
     * @param test   the action
     * @param config the settings that name the subject
     * @return the options; empty if the action takes none or its subject isn't found
     */
    List<String> options(ActionTest test, ActionTesterConfig config) {
        return switch (test) {
            case NPC_OPTION -> npc(config.npc()).map(npc -> listed(npcActions(npc))).orElse(List.of());
            case PLAYER_OPTION -> IntStream.rangeClosed(1, 8).mapToObj(String::valueOf).toList();
            case OBJECT_OPTION -> object(config.object()).map(o -> listed(objectActions(o))).orElse(List.of());
            case GROUND_ITEM_OPTION -> groundItem(config.groundItem()).map(i -> listed(groundActions(i)))
                    .orElse(List.of());
            case INVENTORY_OPTION -> inventorySlot(config.item()).map(slot -> withSubOptions(slot, itemActions(slot)))
                    .orElse(List.of());
            case WIDGET_OPTION -> {
                int[] component = component(config.component());
                Widget widget = component == null ? null : widget(component);
                yield widget == null ? List.of() : withSubOptions(widget, widget.getActions());
            }
            case CAST_SPELL -> {
                int spell = spell(config.spell());
                Widget widget = spell < 0 ? null : client.getWidget(spell);
                yield widget == null ? List.of() : withSubOptions(widget, widget.getActions());
            }
            default -> List.of();
        };
    }

    /**
     * Which op an option is: its number if it is one, else its 1-based place among the actions, matched by text.
     *
     * @param actions the subject's actions by op (1-based in the array's order), null entries for none
     * @param option  the option's text or number
     * @param max     the most ops the subject has
     * @return the op, 1-based, or -1 for no such option
     */
    static int opOf(String[] actions, String option, int max) {
        String wanted = option.trim();
        int number = number(wanted);
        if (number >= 1 && number <= max) {
            return number;
        }
        if (actions == null) {
            return -1;
        }
        for (int i = 0; i < actions.length && i < max; i++) {
            if (actions[i] != null && actions[i].equalsIgnoreCase(wanted)) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * A subject's first option.
     *
     * @param actions the subject's actions by op, null entries for none
     * @return the first op that has a name, 1-based; -1 if none has
     */
    static int firstOp(String[] actions) {
        for (int i = 0; actions != null && i < actions.length; i++) {
            if (named(actions[i])) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * An option setting split into the op and its sub-option: the text after the last dot is the sub-option when
     * there is text on both sides of it.
     *
     * @param text the setting's text
     * @return {op, sub-option}, the sub-option null when there is none
     */
    static String[] splitOption(String text) {
        String trimmed = text.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot <= 0 || dot == trimmed.length() - 1) {
            return new String[]{trimmed, null};
        }
        String op = trimmed.substring(0, dot).trim();
        String sub = trimmed.substring(dot + 1).trim();
        return op.isEmpty() || sub.isEmpty() ? new String[]{trimmed, null} : new String[]{op, sub};
    }

    /**
     * The component of an op, as the menu runs it: {@link MenuAction#CC_OP} for ops 1-5,
     * {@link MenuAction#CC_OP_LOW_PRIORITY} for the rest.
     *
     * @param op the op, 1-based
     * @return the action
     */
    static MenuAction componentAction(int op) {
        return op <= HIGH_PRIORITY_OPS ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY;
    }

    /**
     * A spell's component, as {@link #constantComponent} reads it for the spellbook: High Alchemy is HIGH_ALCHEMY.
     *
     * @param text the setting's text
     * @return the packed component id, or -1 if the text names no spell
     */
    static int spellComponent(String text) {
        return constantComponent(InterfaceID.MagicSpellbook.class, text);
    }

    /**
     * A component one of a gameval class's constants names: given as {@link #parseComponent} reads it, or by the
     * constant's name with spaces, dashes, underscores and case ignored (High Alchemy is HIGH_ALCHEMY, Prayer 19 is
     * PRAYER19).
     *
     * @param type the constants, e.g. {@link InterfaceID.MagicSpellbook}
     * @param text the setting's text
     * @return the packed component id, or -1 if the text names none
     */
    static int constantComponent(Class<?> type, String text) {
        int[] component = parseComponent(text);
        if (component != null) {
            return component[0];
        }
        String wanted = squash(text);
        if (wanted.isEmpty()) {
            return -1;
        }
        return constants(type, name -> squash(name).equals(wanted)).values().stream().findFirst().orElse(-1);
    }

    /**
     * A component as the component setting takes it: as {@link #parseComponent} reads it, or by its name in
     * {@link InterfaceID}, Group.COMPONENT with case ignored (e.g. Orbs.RUNBUTTON). Anything after a space is ignored.
     *
     * @param text the setting's text
     * @return {packed id, slot (-1 for none)}, or null if the text names no component
     */
    static int[] component(String text) {
        int[] component = parseComponent(text);
        if (component != null) {
            return component;
        }
        String[] parts = text.trim().split("\\s+", 2)[0].split("\\.");
        if (parts.length < 2) {
            return null;
        }
        String owner = parts[parts.length - 2];
        String field = parts[parts.length - 1];
        for (Class<?> type : InterfaceID.class.getClasses()) {
            if (type.getSimpleName().equalsIgnoreCase(owner)) {
                return constants(type, name -> name.equalsIgnoreCase(field)).values().stream().findFirst()
                        .map(id -> new int[]{id, -1}).orElse(null);
            }
        }
        return null;
    }

    /**
     * A gameval constant's name as words: HIGH_ALCHEMY is High Alchemy.
     *
     * @param constant the constant's name
     * @return the words, each capitalised
     */
    static String constantName(String constant) {
        return Arrays.stream(constant.toLowerCase(Locale.ROOT).split("_")).filter(word -> !word.isEmpty())
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    /**
     * A component given as group:child, group:child:slot or a packed id, as the text's first word; anything after a
     * space is ignored.
     *
     * @param text the setting's text
     * @return {packed id, slot (-1 for none)}, or null if the text isn't a component
     */
    static int[] parseComponent(String text) {
        String[] words = text.trim().split("\\s+", 2);
        String[] parts = words[0].split(":");
        try {
            if (parts.length == 2 || parts.length == 3) {
                int group = Integer.parseInt(parts[0]);
                int child = Integer.parseInt(parts[1]);
                int slot = parts.length == 3 ? Integer.parseInt(parts[2]) : -1;
                if (group < 0 || child < 0 || group > 0xffff || child > 0xffff) {
                    return null;
                }
                return new int[]{(group << 16) | child, slot};
            }
            if (parts.length == 1 && !parts[0].isBlank()) {
                int packed = Integer.parseInt(parts[0]);
                return packed > 0xffff ? new int[]{packed, -1} : null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /**
     * A component as the component setting takes it, followed by what it shows: its name, its text or its first
     * option.
     *
     * @param widget the component
     * @return e.g. {@code 149:0:3 Coins}
     */
    static String componentText(Widget widget) {
        int id = widget.getId();
        String component = (id >>> 16) + ":" + (id & 0xffff) + (widget.isStatic() ? "" : ":" + widget.getIndex());
        String shows = plain(widget.getName());
        if (shows.isEmpty()) {
            shows = plain(widget.getText());
        }
        if (shows.isEmpty()) {
            shows = listed(widget.getActions()).stream().findFirst().orElse("");
        }
        return shows.isEmpty() ? component : component + " " + shows;
    }

    /**
     * Text without the client's markup, its spaces collapsed.
     *
     * @param text the text, or null
     * @return the plain text; "" for null
     */
    static String plain(String text) {
        return text == null ? "" : TAGS.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    /**
     * A whole number.
     *
     * @param text the text
     * @return the number, or -1 if the text isn't one
     */
    static int number(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Options numbered as the menu numbers them, for the console.
     *
     * @param actions the options by op, null entries for none
     * @return e.g. {@code 1: Talk-to, 3: Attack}
     */
    static String numbered(String[] actions) {
        List<String> names = new ArrayList<>();
        if (actions != null) {
            for (int i = 0; i < actions.length; i++) {
                if (actions[i] != null) {
                    names.add((i + 1) + ": " + actions[i]);
                }
            }
        }
        return names.isEmpty() ? "no options" : String.join(", ", names);
    }

    /**
     * Whether a prayer book constant is a prayer, PRAYER1 to PRAYER30, rather than part of the book's layout.
     *
     * @param constant the constant's name
     * @return true for a prayer
     */
    static boolean isPrayer(String constant) {
        return constant.matches("PRAYER\\d+");
    }

    /**
     * The names the components of a gameval class's constants show now, for those that aren't hidden by themselves;
     * the constants' names as words if none shows one.
     *
     * @param type   the constants
     * @param wanted which constants count
     * @return the names, each once
     */
    private List<String> shownNames(Class<?> type, Predicate<String> wanted) {
        Map<String, Integer> constants = constants(type, wanted);
        Set<String> shown = new LinkedHashSet<>();
        for (Widget widget : widgets(constants.values())) {
            if (widget != null && !widget.isSelfHidden() && !plain(widget.getName()).isEmpty()) {
                shown.add(plain(widget.getName()));
            }
        }
        return shown.isEmpty() ? constants.keySet().stream().map(Subjects::constantName).toList()
                : List.copyOf(shown);
    }

    /**
     * The component a setting names among a gameval class's constants: as {@link #constantComponent} reads it, or the
     * component showing the name, preferring one that isn't hidden by itself.
     *
     * @param type   the constants
     * @param wanted which constants count when matching by the name shown
     * @param text   the setting
     * @return the packed component id, or -1 if the text names none
     */
    private int named(Class<?> type, Predicate<String> wanted, String text) {
        int id = constantComponent(type, text);
        String name = plain(text);
        if (id >= 0 || name.isEmpty()) {
            return id;
        }
        List<Integer> ids = new ArrayList<>(constants(type, wanted).values());
        List<Widget> widgets = widgets(ids);
        int hidden = -1;
        for (int i = 0; i < ids.size(); i++) {
            Widget widget = widgets.get(i);
            if (widget != null && plain(widget.getName()).equalsIgnoreCase(name)) {
                if (!widget.isSelfHidden()) {
                    return ids.get(i);
                }
                if (hidden < 0) {
                    hidden = ids.get(i);
                }
            }
        }
        return hidden;
    }

    /**
     * The widgets of components of one interface, in one read of the interface.
     *
     * @param ids the components' packed ids, all in the same group
     * @return the widgets in the ids' order, null where one isn't loaded
     */
    private List<Widget> widgets(Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Widget[] group = client.getWidgetGroup(ids.iterator().next() >>> 16);
        List<Widget> widgets = new ArrayList<>();
        for (int id : ids) {
            int child = id & 0xffff;
            widgets.add(group == null || child >= group.length ? null : group[child]);
        }
        return widgets;
    }

    /**
     * The int constants of a gameval class whose names pass a test.
     *
     * @param type   the class, e.g. {@link InterfaceID.Prayerbook}
     * @param wanted which names count
     * @return the values by name, in the class's order
     */
    private static Map<String, Integer> constants(Class<?> type, Predicate<String> wanted) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (Field field : type.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class
                    && wanted.test(field.getName())) {
                try {
                    found.put(field.getName(), field.getInt(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(field + " is public but can't be read", e);
                }
            }
        }
        return found;
    }

    /**
     * Text as names are compared loosely: letters and digits only, lower case.
     *
     * @param text the text
     * @return the squashed text
     */
    private static String squash(String text) {
        return text.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String objectName(TileObject object) {
        ObjectComposition composition = client.getObjectDefinition(object.getId());
        return composition == null ? null : composition.getName();
    }

    private String itemName(TileItem item) {
        return itemName(item.getId());
    }

    private List<Widget> inventorySlots() {
        Widget inventory = client.getWidget(INVENTORY);
        Widget[] slots = inventory == null ? null : inventory.getDynamicChildren();
        if (slots == null) {
            return List.of();
        }
        return Arrays.stream(slots).filter(slot -> slot != null && slot.getItemId() > 0).toList();
    }

    /**
     * The components showing in the open interfaces that pass a test: the static ones and their dynamic children.
     *
     * @param fits  the test
     * @param limit the most to return
     * @return the components, in interface order
     */
    private List<Widget> shownComponents(Predicate<Widget> fits, int limit) {
        List<Widget> found = new ArrayList<>();
        for (int group : client.getOpenWidgetGroups()) {
            Widget[] widgets = client.getWidgetGroup(group);
            if (widgets == null) {
                continue;
            }
            for (Widget widget : widgets) {
                if (widget == null || widget.isHidden()) {
                    continue;
                }
                if (fits.test(widget)) {
                    found.add(widget);
                }
                Widget[] children = widget.getDynamicChildren();
                if (children != null) {
                    for (Widget child : children) {
                        if (child != null && !child.isSelfHidden() && fits.test(child)) {
                            found.add(child);
                        }
                    }
                }
                if (found.size() >= limit) {
                    return found.subList(0, limit);
                }
            }
        }
        return found;
    }

    /**
     * The options of a component, each followed by its sub-options after a dot.
     *
     * @param widget  the component
     * @param actions its options by op
     * @return e.g. {@code Drop, Drop.One, Drop.All}
     */
    private static List<String> withSubOptions(Widget widget, String[] actions) {
        List<String> options = new ArrayList<>();
        if (actions == null) {
            return options;
        }
        for (int i = 0; i < actions.length; i++) {
            if (actions[i] == null) {
                continue;
            }
            options.add(actions[i]);
            String[] subOptions = widget.getSubOptions(i + 1);
            if (subOptions != null) {
                for (String sub : subOptions) {
                    if (named(sub)) {
                        options.add(actions[i] + "." + sub);
                    }
                }
            }
        }
        return options;
    }

    /**
     * The loaded thing with a name, nearest to the local player.
     *
     * @param things  what is loaded
     * @param name    the name wanted, case ignored
     * @param nameOf  a thing's name
     * @param placeOf a thing's world tile
     * @param <T>     the kind of thing
     * @return the nearest match, if any
     */
    private <T> Optional<T> nearest(List<T> things, String name, Function<T, String> nameOf,
                                    Function<T, WorldPoint> placeOf) {
        String wanted = name.trim();
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        WorldPoint here = localTile();
        return things.stream()
                .filter(thing -> wanted.equalsIgnoreCase(nameOf.apply(thing)))
                .min(Comparator.comparingInt(thing -> distance(here, placeOf.apply(thing))));
    }

    /**
     * The names of what is loaded, nearest first, each once.
     *
     * @param things  what is loaded
     * @param nameOf  a thing's name
     * @param placeOf a thing's world tile
     * @param <T>     the kind of thing
     * @return at most {@link #MAX_SUGGESTIONS} names
     */
    private <T> List<String> names(List<T> things, Function<T, String> nameOf, Function<T, WorldPoint> placeOf) {
        WorldPoint here = localTile();
        record Named(String name, int distance) {
        }
        Set<String> names = new LinkedHashSet<>();
        things.stream()
                .map(thing -> new Named(nameOf.apply(thing), distance(here, placeOf.apply(thing))))
                .filter(thing -> named(thing.name()))
                .sorted(Comparator.comparingInt(Named::distance))
                .forEach(thing -> names.add(thing.name()));
        return names.stream().limit(MAX_SUGGESTIONS).toList();
    }

    private WorldPoint localTile() {
        Player local = client.getLocalPlayer();
        return local == null ? null : local.getWorldLocation();
    }

    private static int distance(WorldPoint from, WorldPoint to) {
        return from == null || to == null ? Integer.MAX_VALUE : from.distanceTo(to);
    }

    private static boolean hasAny(String[] actions) {
        return actions != null && Arrays.stream(actions).anyMatch(Subjects::named);
    }

    private static List<String> listed(String[] actions) {
        return actions == null ? List.of() : Arrays.stream(actions).filter(Subjects::named).distinct().toList();
    }

    /**
     * Whether a name names something: not empty, and not the "null" unnamed things carry.
     *
     * @param name the name
     * @return true if it is a name
     */
    private static boolean named(String name) {
        return name != null && !name.isBlank() && !name.equalsIgnoreCase("null");
    }
}
