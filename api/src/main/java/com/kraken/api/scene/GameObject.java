package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.ObjectComposition;
import com.kraken.api.internal.Loc;

import java.awt.Polygon;

import static com.kraken.api.Perspective.LOCAL_COORD_BITS;
import static com.kraken.api.Perspective.LOCAL_HALF_TILE_SIZE;

/**
 * A free-standing object, such as a tree, rock, anvil or table. It can cover more than one tile.
 *
 * <p>Its size is its definition's ({@link ObjectComposition#getSizeX()} and {@link ObjectComposition#getSizeY()}),
 * before rotation, since the object's rotation isn't read. The footprint, centre and outline follow the same sizes.
 * The definition is read once, when first needed.
 */
public final class GameObject extends TileObject {

    private volatile int sizeX;
    private volatile int sizeY;

    /**
     * A game object as the scene listing found it.
     *
     * @param client the client
     * @param loc    the object
     * @param baseX  the map base x the scene was read with
     * @param baseY  the map base y
     */
    GameObject(Client client, Loc loc, int baseX, int baseY) {
        super(client, loc, baseX, baseY);
    }

    /**
     * The object's size along x.
     *
     * @return the width in tiles, from its definition; 1 if the definition can't be read
     */
    public int sizeX() {
        return sized() ? sizeX : 1;
    }

    /**
     * The object's size along y.
     *
     * @return the length in tiles, from its definition; 1 if the definition can't be read
     */
    public int sizeY() {
        return sized() ? sizeY : 1;
    }

    /**
     * The object's south-west tile in the scene.
     *
     * @return the scene x and y of the tile it was placed on
     */
    public Point getSceneMinLocation() {
        return new Point(loc.coord().x(), loc.coord().z());
    }

    /**
     * The object's north-east tile in the scene.
     *
     * @return {@link #getSceneMinLocation()} plus the size, less one tile
     */
    public Point getSceneMaxLocation() {
        return new Point(loc.coord().x() + sizeX() - 1, loc.coord().z() + sizeY() - 1);
    }

    /**
     * The footprint's centre: the origin tile's corner plus half the size.
     *
     * @return the local x
     */
    @Override
    public int getX() {
        return (loc.coord().x() << LOCAL_COORD_BITS) + sizeX() * LOCAL_HALF_TILE_SIZE;
    }

    /**
     * The footprint's centre along y.
     *
     * @return the local y
     */
    @Override
    public int getY() {
        return (loc.coord().z() << LOCAL_COORD_BITS) + sizeY() * LOCAL_HALF_TILE_SIZE;
    }

    /**
     * The footprint's centre tile, rounded to the south-west.
     *
     * @return the world tile
     */
    @Override
    public WorldPoint getWorldLocation() {
        return new WorldPoint(baseX + loc.coord().x() + (sizeX() - 1) / 2,
                baseY + loc.coord().z() + (sizeY() - 1) / 2, getPlane());
    }

    /**
     * The outline of the whole footprint.
     *
     * @return the outline, or null if it is outside the scene or a corner is behind the camera
     */
    @Override
    public Polygon getCanvasTilePoly() {
        return Perspective.getCanvasTileAreaPoly(client, getLocalLocation(), sizeX(), sizeY(), getPlane(), 0);
    }

    /**
     * Reads the definition's size the first time it is needed. A definition that can't be read isn't remembered, so
     * it is tried again next time.
     *
     * @return true if the size is known
     */
    private boolean sized() {
        if (sizeX > 0) {
            return true;
        }
        ObjectComposition composition = client.getObjectDefinition(loc.id());
        if (composition == null) {
            return false;
        }
        sizeY = Math.max(1, composition.getSizeY());
        sizeX = Math.max(1, composition.getSizeX()); // written last: sizeX > 0 says both are set
        return true;
    }
}
