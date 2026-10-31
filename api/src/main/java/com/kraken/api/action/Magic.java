package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.widgets.Widget;

/**
 * Casting spells from the spellbook shown. Get it from {@code client.getMagic()} or inject it.
 *
 * <p>A cast on a target selects the spell's component and runs the target's targeted row
 * ({@link Widget#useOn(NPC)}). Runes and levels are left to the server. For a spell {@link Spell} lacks,
 * {@code client.getWidget(InterfaceID.MagicSpellbook.X).useOn(target)} does the same.
 */
public final class Magic {

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
        throw new UnsupportedOperationException("not built yet");
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
        throw new UnsupportedOperationException("not built yet");
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
        throw new UnsupportedOperationException("not built yet");
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
        throw new UnsupportedOperationException("not built yet");
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
        throw new UnsupportedOperationException("not built yet");
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
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * The spell's widget in the spellbook.
     *
     * @param spell the spell
     * @return the widget, or null when the spellbook isn't loaded
     */
    public Widget getSpell(Spell spell) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Whether a spell can be cast from the spellbook shown: the spellbook is the spell's (varbit {@code SPELLBOOK})
     * and the spell's widget isn't hidden by itself.
     *
     * @param spell the spell
     * @return true if it can be cast now
     */
    public boolean isAvailable(Spell spell) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * The spellbook shown.
     *
     * @return the spellbook, by varbit {@code SPELLBOOK}; null if the varbit holds a value no spellbook has
     */
    public Spellbook getSpellbook() {
        throw new UnsupportedOperationException("not built yet");
    }
}
