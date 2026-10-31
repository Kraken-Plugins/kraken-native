package com.kraken.api.action;

/**
 * What became of an action asked for through the interaction methods ({@code npc.interact("Attack")},
 * {@code widget.useOn(npc)}, the services such as {@link Inventory} and {@link Magic}). Game state can change between
 * the check and the send, so a failed check is a result rather than an exception.
 */
public enum ActionResult {

    /**
     * The batch went to the native hook, which runs it at the start of the next game frame. The server can still
     * refuse the action.
     */
    SENT,

    /**
     * The native hook isn't connected, so nothing was sent. Nothing is queued either: try again later.
     */
    NOT_CONNECTED,

    /**
     * This build's mapping lacks the row function, a packet helper or an opcode the action needs, or the option is
     * one no row sends (Examine).
     */
    UNSUPPORTED,

    /**
     * The subject isn't loaded any more: the NPC or player left, the object changed, the ground item was taken, the
     * component closed or its slot holds another item.
     */
    GONE,

    /**
     * The subject has no option with that text or number, the op has no such sub-option, or the selected item or
     * spell can't be used on that kind of target.
     */
    NO_SUCH_OPTION,

    /**
     * A service couldn't find what it was asked for: the item isn't in the inventory, the spell isn't in the
     * spellbook shown, no dialogue is open.
     */
    NOT_FOUND,

    /**
     * A tile to walk to is outside the loaded scene.
     */
    OUT_OF_SCENE;

    /**
     * Whether the action was sent.
     *
     * @return true for {@link #SENT}
     */
    public boolean isSent() {
        return this == SENT;
    }
}
