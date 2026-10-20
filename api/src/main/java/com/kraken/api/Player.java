package com.kraken.api;

/**
 * A player, as RuneLite's {@code Player}. See {@link Actor} for how a handle behaves once the player is gone.
 *
 * <p>There's no {@code isFriend()}: Lua's friend check is "on the friend list and online", and the reader can't tell
 * an offline friend from a stranger, so {@link #isOnlineFriend()} says what it means instead.
 */
public interface Player extends Actor {

    /**
     * The player's index, as RuneLite's {@code getId()}.
     *
     * @return the index
     */
    int getId();

    /**
     * Whether the player is on the local player's friend list and online. Kraken extra, mirrors Lua
     * {@code Player.isOnlineFriend}.
     *
     * @return true for an online friend; false once the player is gone
     */
    boolean isOnlineFriend();

    /**
     * Whether the player is in the local player's friends chat (Lua's {@code isFriendChat}).
     *
     * @return true for a friends chat member
     */
    boolean isFriendsChatMember();

    /**
     * Whether the player is in a clan with the local player (Lua's {@code isGroup}). The client works this out for
     * players it draws, so it reads false until it has.
     *
     * @return true for a clan member
     */
    boolean isClanMember();

    /**
     * Whether the player's name (current or previous) is on the local player's ignore list. Kraken extra, mirrors Lua
     * {@code Player.isIgnored}.
     *
     * @return true if ignored
     */
    boolean isIgnored();
}
