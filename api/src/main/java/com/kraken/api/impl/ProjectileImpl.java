package com.kraken.api.impl;

import com.kraken.api.Actor;
import com.kraken.api.Projectile;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.lua.MapCoord;

/**
 * {@link Projectile} over one Lua mirror {@link com.kraken.api.lua.Projectile} snapshot, with RuneLite's axes (the Lua
 * record keeps the height in {@code y} and north in {@code z}). The endpoint tiles are world tiles already.
 */
final class ProjectileImpl implements Projectile {

    private final ClientImpl client;
    private final com.kraken.api.lua.Projectile projectile;

    ProjectileImpl(ClientImpl client, com.kraken.api.lua.Projectile projectile) {
        this.client = client;
        this.projectile = projectile;
    }

    @Override
    public int getId() {
        return projectile.id();
    }

    @Override
    public int getSourceLevel() {
        return projectile.source().tile().level();
    }

    @Override
    public WorldPoint getSourcePoint() {
        return point(projectile.source().tile());
    }

    @Override
    public Actor getSourceActor() {
        return client.actor(projectile.source().actorType(), projectile.source().actorIndex());
    }

    @Override
    public int getTargetLevel() {
        return projectile.target().tile().level();
    }

    @Override
    public WorldPoint getTargetPoint() {
        return point(projectile.target().tile());
    }

    @Override
    public Actor getTargetActor() {
        return client.actor(projectile.target().actorType(), projectile.target().actorIndex());
    }

    @Override
    public int getStartHeight() {
        return projectile.source().height();
    }

    @Override
    public int getEndHeight() {
        return projectile.target().height();
    }

    @Override
    public int getStartCycle() {
        return projectile.startCycle();
    }

    @Override
    public int getEndCycle() {
        return projectile.endCycle();
    }

    /**
     * The end cycle less the cycle now, as RuneLite works it out; the cycle of the read if the counter can't be read.
     *
     * @return the remaining cycles
     */
    @Override
    public int getRemainingCycles() {
        int cycle = client.getGameCycle();
        return cycle < 0 ? projectile.remainingCycles() : projectile.endCycle() - cycle;
    }

    @Override
    public int getSlope() {
        return projectile.slope();
    }

    @Override
    public int getStartPos() {
        return projectile.startDistance();
    }

    @Override
    public double getX() {
        return projectile.x();
    }

    @Override
    public double getY() {
        return projectile.z();
    }

    @Override
    public double getZ() {
        return projectile.y();
    }

    @Override
    public int getOrientation() {
        return projectile.yaw();
    }

    @Override
    public int getPitch() {
        return projectile.pitch();
    }

    @Override
    public boolean isStarted() {
        return projectile.started();
    }

    @Override
    public boolean isHidden() {
        return projectile.hidden();
    }

    /**
     * The snapshot this projectile answers from.
     *
     * @return the Lua mirror's record
     */
    com.kraken.api.lua.Projectile snapshot() {
        return projectile;
    }

    private static WorldPoint point(MapCoord tile) {
        return new WorldPoint(tile.x(), tile.z(), tile.level());
    }

    @Override
    public String toString() {
        return "Projectile[" + projectile.id() + " " + getSourcePoint() + " -> " + getTargetPoint() + "]";
    }
}
