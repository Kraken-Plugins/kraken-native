package com.kraken.api.menu;

import com.kraken.api.Client;
import com.kraken.api.Menu;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.menu.MenuClick;
import com.kraken.api.internal.menu.MenuHook;
import com.kraken.api.internal.menu.WireRule;
import com.kraken.api.mapping.Mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Changes to the right-click menu, applied by the client's native hook each time the client rebuilds the menu, which
 * it does every client cycle while the menu is closed. The hook holds the rules, so they take effect in the same
 * cycle a row appears, with no round trip: a left click sees the edited menu.
 *
 * <p>Two ways in. {@link #add} keeps a {@link MenuRule} of the plugin's own, such as a swap that makes "Bank" the
 * left click on every banker, until {@link #remove}. The entry setters ({@link MenuEntry#setOption},
 * {@link MenuEntry#setTarget}, {@link MenuEntry#setDeprioritized}, {@link MenuEntry#onClick}),
 * {@link Client#createMenuEntry} and {@link Client#setMenuEntries} make rules on one row, scoped to it: they apply
 * from the next rebuild on and go away once the row has been gone for about half a second. Those are collected and
 * sent together at the end of each pass of the client thread ({@link #flush}), so a created entry is sent once its
 * texts, callback and sub-menu are set.
 *
 * <p>Rules reach the client while its native hook is connected ({@link #isAvailable()}). They are remembered
 * regardless and sent whole whenever the hook (re)connects. Rules added first are applied first.
 *
 * <p>Thread-safe.
 */
public final class MenuEdits {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The op index a row of a plugin's own carries in the client's entry, with its row id as the type id; must match
     * {@code MENU_ROW_OP} in the hook's {@code protocol.h}. The reader turns such an entry back into the created
     * {@link MenuEntry}.
     */
    public static final int ROW_OP = 0x4b524b4e;

    private final Client client;
    private final Map<MenuSubject, Integer> kinds = new EnumMap<>(MenuSubject.class);
    private final int opExamine;
    private final int opTarget;
    private final boolean mapped;

    /**
     * Every rule, by id, in the order to apply them: the plugin's own and the scoped ones behind entries.
     */
    private final Map<Integer, Kept> rules = new LinkedHashMap<>();

    /**
     * The scoped edit rule on each row an entry setter touched, by the row's identity.
     */
    private final Map<MenuMatch, Integer> entryRules = new HashMap<>();

    /**
     * The rows of plugins' own, by row id: created entries and the rows of {@link MenuRule.Add}s.
     */
    private final Map<Integer, MenuEntry> rows = new HashMap<>();

    /**
     * The rule ids behind created top rows.
     */
    private final Map<MenuEntry, Integer> entryRuleIds = new HashMap<>();

    /**
     * Rule ids changed since the last flush, and ids removed since.
     */
    private final Set<Integer> dirty = new LinkedHashSet<>();
    private final Set<Integer> removed = new LinkedHashSet<>();

    private MenuHook hook;
    private boolean sendFailed;
    private int nextRuleId = 1;
    private int nextRowId = 1;

    /**
     * Resolves the subject kinds and op values the rules name once.
     *
     * @param client  the client, whose menu the entries anchor to
     * @param mapping the client build's mapping
     */
    public MenuEdits(Client client, Mapping mapping) {
        this.client = Objects.requireNonNull(client, "client");
        Map<String, Integer> table = mapping.enumTable("minimenuSubject");
        for (MenuSubject kind : MenuSubject.values()) {
            kinds.put(kind, kind == MenuSubject.NONE ? 0 : table.getOrDefault(kind.mappingName(), -1));
        }
        Map<String, Integer> ops = mapping.enumTable("minimenuOp");
        this.opExamine = ops.getOrDefault("EXAMINE", Integer.MIN_VALUE);
        this.opTarget = ops.getOrDefault("TARGET", Integer.MIN_VALUE);
        this.mapped = !table.isEmpty() && mapping.hasFunction("minimenuViewRebuild")
                && mapping.hasFunction("minimenuRun") && mapping.hasFunction("minimenuNodeDestroy");
    }

    /**
     * Whether edits reach the client now: the hook is connected with its minimenu hooks live.
     *
     * @return true while rules reach the client
     */
    public synchronized boolean isAvailable() {
        return hook != null && mapped;
    }

    /**
     * Whether this build's mapping has what the hook needs to edit the menu: the minimenu's rebuild, run and node
     * functions and the subject kinds. Without them nothing is sent, hook or not.
     *
     * @return true if edits can work once the hook connects
     */
    public boolean isSupported() {
        return mapped;
    }

    /**
     * Keeps a rule until {@link #remove}, applying it on every rebuild.
     *
     * @param rule the rule
     * @return the rule's id
     */
    public synchronized int add(MenuRule rule) {
        Objects.requireNonNull(rule, "rule");
        int id = nextRuleId++;
        Kept kept = new Kept(rule);
        if (rule instanceof MenuRule.Add add) {
            kept.row = rowFor(add.row(), null);
        }
        rules.put(id, kept);
        dirty.add(id);
        return id;
    }

    /**
     * Drops a rule.
     *
     * @param id the id {@link #add} returned; an unknown id does nothing
     */
    public synchronized void remove(int id) {
        Kept kept = rules.remove(id);
        if (kept == null) {
            return;
        }
        forget(kept);
        dirty.remove(id);
        removed.add(id);
    }

    /**
     * Drops rules.
     *
     * @param ids the ids
     */
    public synchronized void removeAll(Collection<Integer> ids) {
        for (int id : ids) {
            remove(id);
        }
    }

    /**
     * The ids of the rules kept now, the plugins' own and the scoped ones.
     *
     * @return the ids, in the order the rules apply
     */
    public synchronized List<Integer> ids() {
        return new ArrayList<>(rules.keySet());
    }

    /**
     * Sends what changed since the last call: the rules added or edited (a rule edited back to what the hook holds
     * is left alone), then the ids removed. Internal: the poll loop calls this at the end of each pass of the client
     * thread; a test calls it to see what a setter sends.
     */
    public void flush() {
        MenuHook target;
        List<WireRule> upserts = new ArrayList<>();
        int[] gone;
        synchronized (this) {
            target = hook;
            if (target == null || !mapped) {
                dirty.clear();
                removed.clear();
                return;
            }
            for (int id : dirty) {
                Kept kept = rules.get(id);
                if (kept == null) {
                    continue;
                }
                WireRule wire = compile(id, kept);
                if (!wire.equals(kept.sent)) {
                    kept.sent = wire;
                    upserts.add(wire);
                }
            }
            gone = removed.stream().mapToInt(Integer::intValue).toArray();
            dirty.clear();
            removed.clear();
        }
        if (!upserts.isEmpty()) {
            send(() -> target.upsert(upserts));
        }
        if (gone.length > 0) {
            send(() -> target.remove(gone));
        }
    }

    /**
     * Uses a new connection to the hook, sending every rule through it, or drops the connection. Internal: the
     * client's native hook calls this as its connection comes up and drops.
     *
     * @param hook the connection, or null while there is none
     */
    public void setHook(MenuHook hook) {
        MenuHook target;
        List<WireRule> all = new ArrayList<>();
        synchronized (this) {
            this.hook = hook;
            this.sendFailed = false;
            dirty.clear();
            removed.clear();
            target = hook;
            if (target == null || !mapped) {
                return;
            }
            for (Map.Entry<Integer, Kept> entry : rules.entrySet()) {
                WireRule wire = compile(entry.getKey(), entry.getValue());
                entry.getValue().sent = wire;
                all.add(wire);
            }
        }
        send(() -> target.replace(all));
    }

    /**
     * The created entry a row of a plugin's own reads as. Internal: the menu reader asks for the row id a client
     * entry with {@link #ROW_OP} carries.
     *
     * @param rowId the row id
     * @return the entry, or null for an id no row has
     */
    public synchronized MenuEntry created(int rowId) {
        return rows.get(rowId);
    }

    /**
     * Acts on a click the hook reported: a click on a row of a plugin's own runs the row's callback and is posted as
     * a {@code MenuOptionClicked} by the caller; a click a rule reported on a client row runs the callback set with
     * {@link MenuEntry#onClick} and is posted by the click tracker as the client runs the row. Internal: the session
     * calls this on the client thread.
     *
     * @param click the report
     * @return the created entry to post the click for, or null when nothing is to be posted
     */
    public MenuEntry clicked(MenuClick click) {
        MenuEntry entry;
        Consumer<MenuEntry> callback;
        synchronized (this) {
            if (click.rule()) {
                Kept kept = rules.get(click.id());
                entry = kept == null ? null : kept.subject;
                callback = kept == null ? null : kept.onClick;
            } else {
                entry = rows.get(click.id());
                callback = entry == null ? null : entry.onClick();
            }
        }
        if (entry == null) {
            return null;
        }
        if (callback != null) {
            try {
                callback.accept(entry);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Menu click callback failed", e);
            }
        }
        return click.rule() ? null : entry;
    }

    /**
     * Drops a scoped rule the hook expired: nothing matched it for a while, so its row is gone. Internal: the session
     * calls this on the client thread.
     *
     * @param ruleId the rule's id
     */
    public synchronized void expired(int ruleId) {
        Kept kept = rules.remove(ruleId);
        if (kept != null) {
            forget(kept);
            dirty.remove(ruleId);
        }
    }

    /**
     * Makes a row of the plugin's own next to a row of the menu as it reads now ({@link Client#createMenuEntry}). It
     * is sent at the end of the client thread's pass, with whatever was set on it by then.
     *
     * @param idx the index of the row to put it under in {@link Client#getMenuEntries()}'s order (bottom first), or
     *            -1 to put it at the top
     * @return the row, with its option, target and callback to set
     */
    public MenuEntry create(int idx) {
        MenuEntry[] entries = client.getMenuEntries();
        MenuEntry anchor = null;
        MenuRule.Add.Position position = MenuRule.Add.Position.TOP;
        if (entries.length > 0) {
            if (idx < 0 || idx >= entries.length) {
                anchor = entries[entries.length - 1];
            } else {
                anchor = entries[idx];
                position = MenuRule.Add.Position.BELOW;
            }
        }
        synchronized (this) {
            MenuEntry entry = new MenuEntry(client, nextRowId++, null);
            Kept kept = new Kept(null);
            kept.created = entry;
            kept.anchor = anchorMatch(anchor);
            kept.position = anchor != null && anchor.isCreated() ? positionOf(anchor) : position;
            int id = nextRuleId++;
            kept.row = rowFor(entry);
            rules.put(id, kept);
            rows.put(entry.getRowId(), entry);
            entryRuleIds.put(entry, id);
            dirty.add(id);
            return entry;
        }
    }

    /**
     * Makes the menu show these rows and no others, as far as rules can: a client row left out is hidden, and a
     * client row moved to the top is swapped with the top row, each from the next rebuild on and for as long as the
     * row shows ({@link Client#setMenuEntries}). Other orders aren't expressed; rows of plugins' own are managed by
     * their own rules.
     *
     * @param entries the rows to show, bottom first
     */
    public void setMenuEntries(MenuEntry[] entries) {
        MenuEntry[] current = client.getMenuEntries();
        List<MenuEntry> wanted = new ArrayList<>(List.of(entries));
        for (MenuEntry row : current) {
            if (row.isCreated()) {
                continue;
            }
            if (!wanted.remove(row)) {
                edit(row, MenuRule.Edit.Action.HIDE, true);
            }
        }
        if (entries.length > 0 && current.length > 0) {
            MenuEntry top = entries[entries.length - 1];
            if (!top.isCreated() && !top.equals(current[current.length - 1])) {
                edit(top, MenuRule.Edit.Action.PROMOTE, true);
            }
        }
    }

    /**
     * Writes a client row's option from the next rebuild on.
     *
     * @param entry  the row
     * @param option the text
     */
    public synchronized void setOption(MenuEntry entry, String option) {
        Kept kept = entryRule(entry);
        kept.actions.add(MenuRule.Edit.Action.SET_OPTION);
        kept.option = option;
        changed(kept);
    }

    /**
     * Writes a client row's target from the next rebuild on.
     *
     * @param entry  the row
     * @param target the text
     */
    public synchronized void setTarget(MenuEntry entry, String target) {
        Kept kept = entryRule(entry);
        kept.actions.add(MenuRule.Edit.Action.SET_TARGET);
        kept.target = target;
        changed(kept);
    }

    /**
     * Moves a client row to the bottom from the next rebuild on, or stops doing so.
     *
     * @param entry         the row
     * @param deprioritized true to move it down
     */
    public synchronized void setDeprioritized(MenuEntry entry, boolean deprioritized) {
        edit(entry, MenuRule.Edit.Action.DEPRIORITIZE, deprioritized);
    }

    /**
     * Whether a client row is deprioritized by a rule of an entry setter.
     *
     * @param entry the row
     * @return true if {@link #setDeprioritized} moved it down
     */
    public synchronized boolean isDeprioritized(MenuEntry entry) {
        Integer id = entryRules.get(MenuMatch.of(entry));
        Kept kept = id == null ? null : rules.get(id);
        return kept != null && kept.actions.contains(MenuRule.Edit.Action.DEPRIORITIZE);
    }

    /**
     * Has the hook report clicks on a client row, running a callback for each, or stops.
     *
     * @param entry    the row
     * @param callback the callback, or null to stop
     */
    public synchronized void report(MenuEntry entry, Consumer<MenuEntry> callback) {
        Kept kept = entryRule(entry);
        kept.onClick = callback;
        if (callback == null) {
            kept.actions.remove(MenuRule.Edit.Action.REPORT);
        } else {
            kept.actions.add(MenuRule.Edit.Action.REPORT);
        }
        changed(kept);
    }

    /**
     * Notes that a created row changed, so its rule is sent again at the next flush.
     *
     * @param entry the row, or a sub row of it
     */
    public synchronized void entryChanged(MenuEntry entry) {
        MenuEntry root = entry.getParentRow() != null ? entry.getParentRow() : entry;
        Integer id = entryRuleIds.get(root);
        if (id != null && rules.containsKey(id)) {
            rules.get(id).row = rowFor(root);
            dirty.add(id);
        }
    }

    /**
     * Makes a sub row of a created row.
     *
     * @param parent the created top row
     * @return the sub row
     */
    public synchronized MenuEntry createChild(MenuEntry parent) {
        MenuEntry child = new MenuEntry(client, nextRowId++, parent);
        rows.put(child.getRowId(), child);
        return child;
    }

    /**
     * Adds or removes one action on a client row's scoped edit rule.
     *
     * @param entry  the row
     * @param action the action
     * @param on     true to add it
     */
    private synchronized void edit(MenuEntry entry, MenuRule.Edit.Action action, boolean on) {
        Kept kept = entryRule(entry);
        if (on) {
            kept.actions.add(action);
        } else {
            kept.actions.remove(action);
        }
        changed(kept);
    }

    /**
     * The scoped edit rule on a client row, made if the row has none yet.
     *
     * @param entry the row
     * @return the rule
     */
    private Kept entryRule(MenuEntry entry) {
        if (entry.isCreated()) {
            throw new IllegalArgumentException("a created row has no rule of its own");
        }
        MenuMatch match = MenuMatch.of(entry);
        Integer id = entryRules.get(match);
        Kept kept = id == null ? null : rules.get(id);
        if (kept == null) {
            kept = new Kept(null);
            kept.subject = entry;
            kept.match = match;
            id = nextRuleId++;
            rules.put(id, kept);
            entryRules.put(match, id);
        }
        kept.id = id;
        return kept;
    }

    private void changed(Kept kept) {
        if (kept.actions.isEmpty()) {
            rules.remove(kept.id);
            entryRules.remove(kept.match);
            dirty.remove(kept.id);
            removed.add(kept.id);
        } else {
            dirty.add(kept.id);
        }
    }

    /**
     * Drops what a rule owned: its rows and, for an entry rule, its place in the entry index.
     *
     * @param kept the rule
     */
    private void forget(Kept kept) {
        if (kept.match != null) {
            entryRules.remove(kept.match);
        }
        if (kept.created != null) {
            entryRuleIds.remove(kept.created);
        }
        if (kept.row != null) {
            forgetRows(kept.row);
        }
    }

    private void forgetRows(WireRule.Row row) {
        rows.remove(row.id());
        for (WireRule.Row child : row.children()) {
            forgetRows(child);
        }
    }

    /**
     * The anchor match of a created row: the identity of the row it was created next to, or of that row's anchor
     * when it is a created row itself.
     *
     * @param anchor the row, or null for none
     * @return the match; {@link MenuMatch#any()} for none, which puts the row on top of any menu
     */
    private MenuMatch anchorMatch(MenuEntry anchor) {
        if (anchor == null) {
            return MenuMatch.any();
        }
        if (!anchor.isCreated()) {
            return MenuMatch.of(anchor);
        }
        Integer id = entryRuleIds.get(anchor);
        Kept kept = id == null ? null : rules.get(id);
        return kept == null || kept.anchor == null ? MenuMatch.any() : kept.anchor;
    }

    private MenuRule.Add.Position positionOf(MenuEntry createdAnchor) {
        Integer id = entryRuleIds.get(createdAnchor);
        Kept kept = id == null ? null : rules.get(id);
        return kept == null || kept.position == null ? MenuRule.Add.Position.TOP : kept.position;
    }

    /**
     * The wire row of a created entry and its sub-menu.
     *
     * @param entry the created top row
     * @return the row
     */
    private WireRule.Row rowFor(MenuEntry entry) {
        List<WireRule.Row> children = new ArrayList<>();
        Menu menu = entry.getSubMenu();
        if (menu != null) {
            MenuEntry[] bottomFirst = menu.getMenuEntries();
            for (int i = bottomFirst.length - 1; i >= 0; i--) {
                MenuEntry child = bottomFirst[i];
                children.add(new WireRule.Row(child.getRowId(), child.getOption(), child.getTarget(), List.of()));
            }
        }
        return new WireRule.Row(entry.getRowId(), entry.getOption(), entry.getTarget(), children);
    }

    /**
     * The wire row of a rule's row, making a created entry for it and each sub row so their clicks have an entry.
     *
     * @param row    the row
     * @param parent the created entry of the row above, or null
     * @return the row
     */
    private WireRule.Row rowFor(MenuRow row, MenuEntry parent) {
        MenuEntry entry = new MenuEntry(client, nextRowId++, parent);
        entry.setOption(row.option()).setTarget(row.target());
        if (row.onClick() != null) {
            entry.onClick(row.onClick());
        }
        rows.put(entry.getRowId(), entry);
        List<WireRule.Row> children = new ArrayList<>();
        if (parent == null) {
            for (MenuRow child : row.children()) {
                children.add(rowFor(child, entry));
            }
        }
        return new WireRule.Row(entry.getRowId(), row.option(), row.target(), children);
    }

    /**
     * Compiles a kept rule into what the hook takes.
     *
     * @param id   the rule's id
     * @param kept the rule
     * @return the wire rule
     */
    private WireRule compile(int id, Kept kept) {
        if (kept.created != null) {
            return new WireRule(id, WireRule.Type.ADD, ShiftState.ANY, true, false, compile(kept.anchor),
                    EnumSet.noneOf(MenuRule.Edit.Action.class), "", "", null, false, false, false, false,
                    kept.position, kept.row);
        }
        if (kept.match != null) {
            return new WireRule(id, WireRule.Type.EDIT, ShiftState.ANY, true, false, compile(kept.match),
                    EnumSet.copyOf(kept.actions), kept.option, kept.target, null, false, false, false, false, null,
                    null);
        }
        MenuRule rule = kept.rule;
        if (rule instanceof MenuRule.Edit edit) {
            return new WireRule(id, WireRule.Type.EDIT, edit.shift(), edit.scoped(), edit.finalRow(),
                    compile(edit.match()), edit.actions(), edit.option(), edit.target(), null, false, false, false,
                    false, null, null);
        }
        if (rule instanceof MenuRule.Swap swap) {
            return new WireRule(id, WireRule.Type.SWAP, swap.shift(), false, false, compile(swap.trigger()),
                    EnumSet.noneOf(MenuRule.Edit.Action.class), "", "", compile(swap.swapped()), swap.sameTarget(),
                    swap.sameSubject(), swap.below(), swap.toTop(), null, null);
        }
        MenuRule.Add add = (MenuRule.Add) rule;
        return new WireRule(id, WireRule.Type.ADD, add.shift(), add.scoped(), false, compile(add.anchor()),
                EnumSet.noneOf(MenuRule.Edit.Action.class), "", "", null, false, false, false, false, add.position(),
                kept.row);
    }

    /**
     * Compiles a match into the mapping's numbers: the kind from the match or its action, the op index from the
     * action and op number, and the rest as given.
     *
     * @param match the match
     * @return the wire match
     */
    WireRule.Match compile(MenuMatch match) {
        Set<WireRule.Match.Field> mask = EnumSet.noneOf(WireRule.Match.Field.class);
        int kind = 0;
        int op = 0;
        TextMatch option = match.option();
        MenuSubject subject = match.kind();
        if (match.action() != null) {
            subject = MenuMatch.kindOf(match.action());
            Integer index = opIndex(match.action(), match.identifier());
            if (index != null) {
                mask.add(WireRule.Match.Field.OP);
                op = index;
            }
            if (match.action() == MenuAction.CANCEL && option == null) {
                option = TextMatch.exact("Cancel");
            }
        }
        if (subject != null) {
            mask.add(WireRule.Match.Field.KIND);
            kind = kinds.getOrDefault(subject, 0);
        }
        if (match.typeId() != null) {
            mask.add(WireRule.Match.Field.TYPE_ID);
        }
        if (match.index() != null) {
            mask.add(WireRule.Match.Field.INDEX);
        }
        if (match.group() != null) {
            mask.add(WireRule.Match.Field.GROUP);
        }
        if (match.tile() != null) {
            mask.add(WireRule.Match.Field.TILE);
        }
        if (option != null) {
            mask.add(WireRule.Match.Field.OPTION);
        }
        if (match.target() != null) {
            mask.add(WireRule.Match.Field.TARGET);
        }
        WorldPoint tile = match.tile();
        return new WireRule.Match(mask, kind, match.typeId() == null ? 0 : match.typeId(),
                match.index() == null ? 0 : match.index(), op, match.group() == null ? 0 : match.group(),
                tile == null ? 0 : tile.getPlane(), tile == null ? 0 : tile.getX(), tile == null ? 0 : tile.getY(),
                option, match.target(), match.subMenuRows());
    }

    /**
     * The op index the client's entry holds for an action, the inverse of how the reader names it.
     *
     * @param action     the action
     * @param identifier the op number of a component op, or null for any
     * @return the op index, or null when the action matches any op (Walk here, the old-style buttons) or the mapping
     * doesn't name it
     */
    private Integer opIndex(MenuAction action, Integer identifier) {
        return switch (action) {
            case NPC_FIRST_OPTION, GAME_OBJECT_FIRST_OPTION, GROUND_ITEM_FIRST_OPTION, PLAYER_FIRST_OPTION -> 1;
            case NPC_SECOND_OPTION, GAME_OBJECT_SECOND_OPTION, GROUND_ITEM_SECOND_OPTION, PLAYER_SECOND_OPTION -> 2;
            case NPC_THIRD_OPTION, GAME_OBJECT_THIRD_OPTION, GROUND_ITEM_THIRD_OPTION, PLAYER_THIRD_OPTION -> 3;
            case NPC_FOURTH_OPTION, GAME_OBJECT_FOURTH_OPTION, GROUND_ITEM_FOURTH_OPTION, PLAYER_FOURTH_OPTION -> 4;
            case NPC_FIFTH_OPTION, GAME_OBJECT_FIFTH_OPTION, GROUND_ITEM_FIFTH_OPTION, PLAYER_FIFTH_OPTION -> 5;
            case PLAYER_SIXTH_OPTION -> 6;
            case PLAYER_SEVENTH_OPTION -> 7;
            case PLAYER_EIGHTH_OPTION -> 8;
            case EXAMINE_NPC, EXAMINE_OBJECT, EXAMINE_ITEM_GROUND -> opExamine == Integer.MIN_VALUE ? null : opExamine;
            case WIDGET_TARGET_ON_NPC, WIDGET_TARGET_ON_PLAYER, WIDGET_TARGET_ON_GAME_OBJECT,
                 WIDGET_TARGET_ON_GROUND_ITEM, WIDGET_TARGET_ON_WIDGET -> opTarget == Integer.MIN_VALUE ? null : opTarget;
            case CC_OP, CC_OP_LOW_PRIORITY -> identifier == null ? null : identifier - 1;
            case RUNELITE -> ROW_OP;
            default -> null;
        };
    }

    /**
     * Sends through the hook, logging a broken connection once.
     *
     * @param action the send
     */
    private void send(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            synchronized (this) {
                if (!sendFailed) {
                    sendFailed = true;
                    log.log(Level.WARNING, "Could not send menu rules to the native hook", e);
                }
            }
        }
    }

    /**
     * One kept rule: a plugin's {@link MenuRule}, the scoped edit behind an entry's setters (a match, its actions
     * and texts, and the row for its callback), or the add behind a created entry (its anchor and position).
     */
    private static final class Kept {
        final MenuRule rule;
        int id;
        MenuMatch match;
        MenuEntry subject;
        final Set<MenuRule.Edit.Action> actions = EnumSet.noneOf(MenuRule.Edit.Action.class);
        String option = "";
        String target = "";
        Consumer<MenuEntry> onClick;
        MenuEntry created;
        MenuMatch anchor;
        MenuRule.Add.Position position;
        WireRule.Row row;
        WireRule sent;

        Kept(MenuRule rule) {
            this.rule = rule;
        }
    }
}
