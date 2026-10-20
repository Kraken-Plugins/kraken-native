package com.kraken.api.actor;

import com.kraken.api.Client;

/**
 * A player, as RuneLite's {@code Player}. {@link Client} hands out one handle per player, so identity equality is
 * player equality; see {@link Actor} for how a handle behaves once the player is gone.
 *
 * <p>There's no {@code isFriend()}: the client's friend check is "on the friend list and online", and the reader can't
 * tell an offline friend from a stranger, so {@link #isOnlineFriend()} says what it means instead.
 */
public final class Player extends Actor {

    /**
     * The pinned player's display name: a new player can be allocated where the old one was.
     */
    private final String pinnedName;

    /**
     * A handle pinned to the player at an index now.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param client        the client
     * @param index         the player's index
     * @param pinnedAddress the player's address
     * @param pinnedName    the player's display name
     */
    public Player(Client client, int index, long pinnedAddress, String pinnedName) {
        super(client, index, pinnedAddress);
        this.pinnedName = pinnedName;
    }

    @Override
    long address() {
        long address = actors.player(index);
        return address != pinnedAddress || !actors.playerName(address).equals(pinnedName) ? 0 : address;
    }

    @Override
    String readName(long address) {
        return actors.playerName(address);
    }

    /**
     * Whether this handle is for the player at an address with a name.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param address the player's address
     * @param name    the player's display name
     * @return true if the handle is pinned to that player
     */
    public boolean isPinnedTo(long address, String name) {
        return pinnedAddress == address && pinnedName.equals(name);
    }

    /**
     * The player's index, as RuneLite's {@code getId()}.
     *
     * @return the index
     */
    public int getId() {
        return index;
    }

    /**
     * A player stands on one tile.
     *
     * @return 1
     */
    @Override
    public int getFootprintSize() {
        return 1;
    }

    /**
     * Whether the player is on the local player's friend list and online. Kraken extra, mirrors Lua
     * {@code Player.isOnlineFriend}: reads the answer the client caches on the player, or looks the name up in the
     * friend list when the client hasn't yet.
     *
     * @return true for an online friend; false once the player is gone
     */
    public boolean isOnlineFriend() {
        return actors.playerIsOnlineFriend(address());
    }

    /**
     * Whether the player is in the local player's friends chat (Lua's {@code isFriendChat}).
     *
     * @return true for a friends chat member
     */
    public boolean isFriendsChatMember() {
        return actors.playerIsFriendChat(address());
    }

    /**
     * Whether the player is in a clan with the local player (Lua's {@code isGroup}). The client works this out for
     * players it draws, so it reads false until it has.
     *
     * @return true for a clan member
     */
    public boolean isClanMember() {
        return actors.playerIsGroup(address());
    }

    /**
     * Whether the player's name (current or previous) is on the local player's ignore list. Kraken extra, mirrors Lua
     * {@code Player.isIgnored}.
     *
     * @return true if ignored
     */
    public boolean isIgnored() {
        return actors.playerIsIgnored(address());
    }

    @Override
    public String toString() {
        return "Player[" + index + "]";
    }
}
