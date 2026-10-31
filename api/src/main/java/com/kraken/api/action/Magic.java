package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.gameval.VarbitID;
import com.kraken.api.internal.action.Interactions;
import com.kraken.api.internal.action.OptionMatcher;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.widgets.Widget;

import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Casting spells from the spellbook shown. Get it from {@code client.getMagic()} or inject it.
 *
 * <p>A cast on a target selects the spell's component and runs the target's targeted row
 * ({@link Widget#useOn(NPC)}). Runes and levels are left to the server. For a spell {@link Spell} lacks,
 * {@code client.getWidget(InterfaceID.MagicSpellbook.X).useOn(target)} does the same.
 */
public final class Magic {

    private static final Logger log = Logger.getLogger("kraken.api");

    private final Client client;

    /**
     * Creates the service. {@code Client} makes the one plugins use.
     *
     * @param client the client
     */
    public Magic(Client client) {
        this.client = client;
    }

    /**
     * Casts a spell that takes no target, such as a teleport: its own Cast option, else its first option.
     *
     * @param spell the spell
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the spell isn't in
     *         the spellbook shown
     */
    public ActionResult cast(Spell spell) {
        Widget widget = available(spell);
        if (widget == null) {
            return ActionResult.NOT_FOUND;
        }
        String[] options = client.getInteractions().actions(widget);
        if (OptionMatcher.match(options, "Cast", Interactions.COMPONENT_OPS) > 0) {
            return widget.interact("Cast");
        }
        int op = OptionMatcher.first(options, Interactions.COMPONENT_OPS);
        if (op < 0) {
            log.fine(() -> spell + " has no option; it has " + OptionMatcher.describe(options));
            return ActionResult.NO_SUCH_OPTION;
        }
        return widget.interact(op);
    }

    /**
     * Casts a spell on an NPC.
     *
     * @param spell  the spell
     * @param target the NPC
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if the spell isn't in
     *         the spellbook shown, {@link ActionResult#NO_SUCH_OPTION} if it can't target an NPC
     */
    public ActionResult cast(Spell spell, NPC target) {
        return castOn(spell, widget -> widget.useOn(target));
    }

    /**
     * Casts a spell on a player.
     *
     * @param spell  the spell
     * @param target the player
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if the spell isn't in
     *         the spellbook shown, {@link ActionResult#NO_SUCH_OPTION} if it can't target a player
     */
    public ActionResult cast(Spell spell, Player target) {
        return castOn(spell, widget -> widget.useOn(target));
    }

    /**
     * Casts a spell on an object.
     *
     * @param spell  the spell
     * @param target the object
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if the spell isn't in
     *         the spellbook shown, {@link ActionResult#NO_SUCH_OPTION} if it can't target an object
     */
    public ActionResult cast(Spell spell, TileObject target) {
        return castOn(spell, widget -> widget.useOn(target));
    }

    /**
     * Casts a spell on an item on the ground, as Telekinetic Grab is cast.
     *
     * @param spell  the spell
     * @param target the ground item
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if the spell isn't in
     *         the spellbook shown, {@link ActionResult#NO_SUCH_OPTION} if it can't target a ground item
     */
    public ActionResult cast(Spell spell, TileItem target) {
        return castOn(spell, widget -> widget.useOn(target));
    }

    /**
     * Casts a spell on an inventory item, as High Alchemy and Superheat Item are cast.
     *
     * @param spell  the spell
     * @param itemId the inventory item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if the spell isn't in
     *         the spellbook shown or the item isn't held, {@link ActionResult#NO_SUCH_OPTION} if it can't target an
     *         item
     */
    public ActionResult cast(Spell spell, int itemId) {
        Widget widget = available(spell);
        if (widget == null) {
            return ActionResult.NOT_FOUND;
        }
        Widget slot = client.getInventory().getSlot(itemId);
        if (slot == null) {
            log.fine(() -> "no inventory slot shown holds item " + itemId + " for " + spell);
            return ActionResult.NOT_FOUND;
        }
        return widget.useOn(slot);
    }

    /**
     * The spell's widget in the spellbook.
     *
     * @param spell the spell
     * @return the widget, or null when the spellbook isn't loaded
     */
    public Widget getSpell(Spell spell) {
        return client.getWidget(spell.getComponentId());
    }

    /**
     * Whether a spell can be cast from the spellbook shown: the spellbook is the spell's (varbit {@code SPELLBOOK})
     * and the spell's widget isn't hidden by itself.
     *
     * @param spell the spell
     * @return true if it can be cast now
     */
    public boolean isAvailable(Spell spell) {
        return available(spell) != null;
    }

    /**
     * The spellbook shown.
     *
     * @return the spellbook, by varbit {@code SPELLBOOK}; null if the varbit holds a value no spellbook has
     */
    public Spellbook getSpellbook() {
        return Spellbook.of(client.getVarbitValue(VarbitID.SPELLBOOK));
    }

    /**
     * The spell's widget if the spell can be cast from the spellbook shown.
     *
     * @param spell the spell
     * @return the widget, or null if the spellbook shown isn't the spell's or the widget is missing or hidden by itself
     */
    private Widget available(Spell spell) {
        Spellbook shown = getSpellbook();
        if (shown != spell.getSpellbook()) {
            log.fine(() -> spell + " is in the " + spell.getSpellbook() + " spellbook; the shown one is " + shown);
            return null;
        }
        Widget widget = getSpell(spell);
        if (widget == null || widget.isSelfHidden()) {
            log.fine(() -> spell + " isn't loaded or is hidden");
            return null;
        }
        return widget;
    }

    /**
     * Runs an action on the spell's widget when the spell can be cast.
     *
     * @param spell  the spell
     * @param action what to do with the widget
     * @return the action's result, or {@link ActionResult#NOT_FOUND} if the spell can't be cast now
     */
    private ActionResult castOn(Spell spell, Function<Widget, ActionResult> action) {
        Widget widget = available(spell);
        return widget == null ? ActionResult.NOT_FOUND : action.apply(widget);
    }
}
