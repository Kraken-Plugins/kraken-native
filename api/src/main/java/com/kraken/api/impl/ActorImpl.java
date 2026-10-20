package com.kraken.api.impl;

import com.kraken.api.Actor;
import com.kraken.api.WorldView;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldArea;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.lua.Entity;
import com.kraken.api.lua.FineCoord;
import com.kraken.api.lua.MapCoord;

/**
 * What {@link NpcImpl} and {@link PlayerImpl} share: an {@link Actor} over a pinned Lua handle
 * ({@code ClientOp.pinNpc}/{@code pinPlayer}), which reads as "not loaded" once its actor is gone.
 *
 * <p>Getters whose "not loaded" value can't be told from a real one (an empty name, no location) check whether the
 * actor is still there before believing it, and keep the last real value for after the actor has gone.
 */
abstract class ActorImpl implements Actor {

    final ClientImpl client;
    private final Entity lua;
    private volatile String name = "";
    private volatile WorldPoint worldLocation;
    private volatile LocalPoint localLocation;

    ActorImpl(ClientImpl client, Entity lua) {
        this.client = client;
        this.lua = lua;
    }

    @Override
    public String getName() {
        String live = lua.getName();
        if (live.isEmpty() && !lua.exists()) {
            return name;
        }
        name = live;
        return live;
    }

    @Override
    public boolean isInteracting() {
        return lua.isInteracting() != -1;
    }

    @Override
    public Actor getInteracting() {
        int index = lua.isInteracting();
        if (index == -1) {
            return null;
        }
        return switch (lua.getInteractingType()) {
            case Entity.TARGET_NPC -> client.npc(index);
            case Entity.TARGET_PLAYER -> client.player(index);
            default -> null;
        };
    }

    @Override
    public int getInteractingIndex() {
        return lua.isInteracting();
    }

    @Override
    public int getInteractingType() {
        return lua.getInteractingType();
    }

    @Override
    public WorldPoint getWorldLocation() {
        MapCoord tile = lua.getCoord();
        WorldPoint world = tile == null ? null : client.toWorld(tile);
        if (world == null) {
            return worldLocation;
        }
        worldLocation = world;
        return world;
    }

    @Override
    public LocalPoint getLocalLocation() {
        FineCoord fine = lua.getCoordFine();
        if (fine.isMissing()) {
            return localLocation;
        }
        LocalPoint local = new LocalPoint(fine.x(), fine.z(), WorldView.TOPLEVEL);
        localLocation = local;
        return local;
    }

    @Override
    public WorldArea getWorldArea() {
        WorldPoint at = getWorldLocation();
        if (at == null) {
            return null;
        }
        int size = getFootprintSize();
        return new WorldArea(at, size, size);
    }

    @Override
    public WorldView getWorldView() {
        return client.getTopLevelWorldView();
    }

    @Override
    public boolean isDead() {
        return lua.isDead();
    }

    @Override
    public boolean isHealthbarRendered() {
        return lua.isHealthbarRendered();
    }

    @Override
    public boolean isHeadbarEmpty() {
        return lua.isHeadbarEmpty();
    }

    @Override
    public boolean isRendered2D() {
        return lua.isRendered2D();
    }

    @Override
    public boolean isHidden() {
        return !lua.isRendered3D();
    }

    /**
     * Reads the values a handle keeps once its actor is gone (name and location), so they are fresh when it goes. The
     * event source calls this when an actor spawns and once per game tick while it tracks the actor.
     */
    void remember() {
        getName();
        getWorldLocation();
        getLocalLocation();
    }
}
