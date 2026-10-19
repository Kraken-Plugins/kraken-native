package com.kraken.api.game;

/**
 * Mirrors the Lua {@code osrs.Player} object that {@code ClientOp.getPlayerObj} returns: a live handle on a player
 * index (see {@link Entity} for what "live" means). Get one from {@link Client#getPlayerObj},
 * {@link Client#getPlayers} or {@link Client#getLocalPlayer}.
 */
public final class Player implements Entity {

    private final ActorReader actors;
    private final int index;

    Player(ActorReader actors, int index) {
        this.actors = actors;
        this.index = index;
    }

    private long address() {
        return actors.player(index);
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

    @Override
    public boolean equals(Object o) {
        return o instanceof Player other && other.index == index && other.actors == actors;
    }

    @Override
    public int hashCode() {
        return index;
    }

    @Override
    public String toString() {
        return "Player[" + index + "]";
    }
}
