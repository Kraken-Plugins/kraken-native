package com.kraken.api.impl;

import com.kraken.api.Player;

/**
 * {@link Player} over a pinned Lua {@code Player} handle. {@link ClientImpl#player} hands out one per player, so
 * identity equality is player equality.
 */
final class PlayerImpl extends ActorImpl implements Player {

    private final com.kraken.api.lua.Player lua;

    PlayerImpl(ClientImpl client, com.kraken.api.lua.Player lua) {
        super(client, lua);
        this.lua = lua;
    }

    /**
     * The pinned Lua handle, which says which player this is.
     *
     * @return the handle this one reads through
     */
    com.kraken.api.lua.Player pin() {
        return lua;
    }

    @Override
    public int getId() {
        return lua.index();
    }

    @Override
    public int getFootprintSize() {
        return 1;
    }

    @Override
    public boolean isOnlineFriend() {
        return lua.isOnlineFriend();
    }

    @Override
    public boolean isFriendsChatMember() {
        return lua.isFriendChat();
    }

    @Override
    public boolean isClanMember() {
        return lua.isGroup();
    }

    @Override
    public boolean isIgnored() {
        return lua.isIgnored();
    }

    @Override
    public String toString() {
        return "Player[" + getId() + "]";
    }
}
