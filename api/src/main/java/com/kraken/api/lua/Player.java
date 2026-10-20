package com.kraken.api.lua;

/**
 * Mirrors the Lua {@code osrs.Player} object that {@code ClientOp.getPlayerObj} returns: a live handle on a player
 * index (see {@link Entity} for what "live" means). Get one from {@link ClientOp#getPlayerObj},
 * {@link ClientOp#getPlayers} or {@link ClientOp#getLocalPlayer}.
 *
 * <p>{@link ClientOp#pinPlayer} makes a pinned handle instead, a Java extra: it stays on the player that held the
 * index when it was made, and reads as "not loaded" once another player takes the index.
 */
public final class Player implements Entity {

    private final ActorReader actors;
    private final int index;
    /**
     * The player this handle is pinned to, or 0 for a handle that follows the index.
     */
    private final long pinnedAddress;
    /**
     * The pinned player's display name: a new player can be allocated where the old one was.
     */
    private final String pinnedName;

    Player(ActorReader actors, int index) {
        this(actors, index, 0, "");
    }

    Player(ActorReader actors, int index, long pinnedAddress, String pinnedName) {
        this.actors = actors;
        this.index = index;
        this.pinnedAddress = pinnedAddress;
        this.pinnedName = pinnedName;
    }

    /**
     * The player at this handle's index, if it's the one the handle is for.
     *
     * @return its address; 0 if nothing is loaded at the index, or (pinned) another player has taken it
     */
    private long address() {
        long address = actors.player(index);
        if (pinnedAddress != 0 && (address != pinnedAddress || !actors.playerName(address).equals(pinnedName))) {
            return 0;
        }
        return address;
    }

    @Override
    public int index() {
        return index;
    }

    @Override
    public boolean exists() {
        return address() != 0;
    }

    @Override
    public String getName() {
        return actors.playerName(address());
    }

    @Override
    public int isInteracting() {
        return actors.interacting(address());
    }

    @Override
    public int getInteractingType() {
        return actors.interactingType(address());
    }

    @Override
    public boolean isDead() {
        return actors.dead(address());
    }

    @Override
    public boolean isHealthbarRendered() {
        return actors.healthbarRendered(address());
    }

    @Override
    public boolean isHeadbarEmpty() {
        return actors.headbarEmpty(address());
    }

    @Override
    public boolean isRendered3D() {
        return actors.rendered3D(address());
    }

    @Override
    public boolean isRendered2D() {
        return actors.rendered2D(address());
    }

    @Override
    public MapCoord getCoord() {
        return actors.coord(address());
    }

    @Override
    public FineCoord getCoordFine() {
        return actors.fineCoord(address());
    }

    /**
     * Mirrors {@code isOnlineFriend}: the player is on the local player's friend list and online. Reads the answer the
     * client caches on the player, or looks the name up in the friend list when the client hasn't yet.
     *
     * @return true if the player is an online friend; false if not, or if the player isn't loaded
     */
    public boolean isOnlineFriend() {
        return actors.playerIsOnlineFriend(address());
    }

    /**
     * Mirrors {@code isGroup}: the player is in a clan with the local player. Reads the answer the client caches on the
     * player (it fills it in for players it draws).
     *
     * @return true if the client has worked out that the player is in a clan with you; false otherwise, including while
     *         the client hasn't worked it out yet
     */
    public boolean isGroup() {
        return actors.playerIsGroup(address());
    }

    /**
     * Mirrors {@code isIgnored}.
     *
     * @return true if the player's name (current or previous) is on the local player's ignore list
     */
    public boolean isIgnored() {
        return actors.playerIsIgnored(address());
    }

    /**
     * Mirrors {@code isFriendChat}. Reads the answer the client caches on the player, or looks the name up in the
     * friends chat when the client hasn't yet.
     *
     * @return true if the player is in the local player's friends chat
     */
    public boolean isFriendChat() {
        return actors.playerIsFriendChat(address());
    }

    /**
     * Mirrors {@code isLocalPlayer}. Like the client, this doesn't check that the player is loaded.
     *
     * @return true if this handle's index is the local player's index
     */
    public boolean isLocalPlayer() {
        return actors.localPlayerIndex() == index;
    }

    /**
     * Two handles are equal if they read the same thing: the same index, and for pinned handles the same player.
     *
     * @param o the other object
     * @return true for a handle on the same index with the same pin (or neither pinned)
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof Player other && other.index == index && other.actors == actors
                && other.pinnedAddress == pinnedAddress && other.pinnedName.equals(pinnedName);
    }

    @Override
    public int hashCode() {
        return index;
    }

    @Override
    public String toString() {
        return pinnedAddress == 0 ? "Player[" + index + "]" : "Player[" + index + " pinned]";
    }
}
