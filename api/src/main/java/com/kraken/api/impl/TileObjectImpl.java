package com.kraken.api.impl;

import com.kraken.api.DecorativeObject;
import com.kraken.api.GameObject;
import com.kraken.api.GroundObject;
import com.kraken.api.ObjectComposition;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.TileObject;
import com.kraken.api.WallObject;
import com.kraken.api.WorldView;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.lua.Loc;
import com.kraken.api.lua.MapCoord;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.Rectangle2D;
import java.util.Objects;

import static com.kraken.api.Perspective.LOCAL_COORD_BITS;
import static com.kraken.api.Perspective.LOCAL_HALF_TILE_SIZE;

/**
 * {@link TileObject} over a {@link Loc} from the Java scene listing, with the map base the scene was read with. One
 * nested class per layer gives the four RuneLite types. Objects are values: equal when they are the same kind with
 * the same loc and base.
 */
abstract class TileObjectImpl implements TileObject {

    /**
     * RuneLite's tag type for an object, at bits 16-18 of {@link #getHash()} (the native tag uses the same layout).
     */
    private static final long HASH_TYPE_OBJECT = 2;

    final ClientImpl client;
    final Loc loc;
    final int baseX;
    final int baseY;

    TileObjectImpl(ClientImpl client, Loc loc, int baseX, int baseY) {
        this.client = client;
        this.loc = loc;
        this.baseX = baseX;
        this.baseY = baseY;
    }

    /**
     * The adapter for a loc's layer.
     *
     * @param client the client
     * @param loc    the loc, as the scene listing returns it
     * @param baseX  the map base x the scene was read with
     * @param baseY  the map base y
     * @return a {@link WallObject}, {@link DecorativeObject}, {@link GameObject} or {@link GroundObject}
     */
    static TileObjectImpl of(ClientImpl client, Loc loc, int baseX, int baseY) {
        return switch (loc.layer()) {
            case Loc.WALL -> new Wall(client, loc, baseX, baseY);
            case Loc.WALL_DECOR -> new Decorative(client, loc, baseX, baseY);
            case Loc.GAME_OBJECT -> new Game(client, loc, baseX, baseY);
            default -> new Ground(client, loc, baseX, baseY);
        };
    }

    @Override
    public long getHash() {
        MapCoord at = loc.coord();
        return (at.x() & 0x7f)
                | (long) (at.z() & 0x7f) << 7
                | (long) (at.level() & 3) << HASH_PLANE_SHIFT
                | HASH_TYPE_OBJECT << 16
                | (loc.id() & 0xffffffffL) << 20
                | (long) (WorldView.TOPLEVEL & 0xfff) << 52;
    }

    @Override
    public int getX() {
        return (loc.coord().x() << LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE;
    }

    @Override
    public int getY() {
        return (loc.coord().z() << LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE;
    }

    @Override
    public int getZ() {
        return getWorldView().getTileHeight(getX(), getY(), getPlane());
    }

    @Override
    public int getPlane() {
        return loc.coord().level();
    }

    @Override
    public WorldView getWorldView() {
        return client.getTopLevelWorldView();
    }

    @Override
    public int getId() {
        return loc.id();
    }

    @Override
    public WorldPoint getWorldLocation() {
        return new WorldPoint(baseX + loc.coord().x(), baseY + loc.coord().z(), getPlane());
    }

    @Override
    public LocalPoint getLocalLocation() {
        return new LocalPoint(getX(), getY(), WorldView.TOPLEVEL);
    }

    @Override
    public Point getCanvasLocation() {
        return getCanvasLocation(0);
    }

    @Override
    public Point getCanvasLocation(int zOffset) {
        return Perspective.localToCanvas(client, getLocalLocation(), getPlane(), zOffset);
    }

    @Override
    public Polygon getCanvasTilePoly() {
        return Perspective.getCanvasTileAreaPoly(client, getLocalLocation(), 1, 1, getPlane(), 0);
    }

    /**
     * As {@link Perspective#getCanvasTextLocation}, on the object's own plane rather than its world view's.
     *
     * @param graphics the graphics the text will be drawn with (its font sets the width)
     * @param text     the text
     * @param zOffset  how far above the ground, in local units
     * @return the baseline's left end, or null if the text is null, or the object is outside the scene or behind the
     *         camera
     */
    @Override
    public Point getCanvasTextLocation(Graphics2D graphics, String text, int zOffset) {
        if (text == null) {
            return null;
        }
        Point at = getCanvasLocation(zOffset);
        if (at == null) {
            return null;
        }
        FontMetrics metrics = graphics.getFontMetrics();
        Rectangle2D bounds = metrics.getStringBounds(text, graphics);
        return new Point(at.getX() - (int) (bounds.getWidth() / 2), at.getY());
    }

    @Override
    public boolean equals(Object other) {
        return other != null && other.getClass() == getClass() && loc.equals(((TileObjectImpl) other).loc)
                && baseX == ((TileObjectImpl) other).baseX && baseY == ((TileObjectImpl) other).baseY;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), loc, baseX, baseY);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "Object[" + loc.id() + " at " + getWorldLocation() + "]";
    }

    /**
     * A wall.
     */
    static final class Wall extends TileObjectImpl implements WallObject {
        Wall(ClientImpl client, Loc loc, int baseX, int baseY) {
            super(client, loc, baseX, baseY);
        }
    }

    /**
     * A wall decoration.
     */
    static final class Decorative extends TileObjectImpl implements DecorativeObject {
        Decorative(ClientImpl client, Loc loc, int baseX, int baseY) {
            super(client, loc, baseX, baseY);
        }
    }

    /**
     * A floor decoration.
     */
    static final class Ground extends TileObjectImpl implements GroundObject {
        Ground(ClientImpl client, Loc loc, int baseX, int baseY) {
            super(client, loc, baseX, baseY);
        }
    }

    /**
     * A game object, sized by its definition (read once, when first needed).
     */
    static final class Game extends TileObjectImpl implements GameObject {
        private volatile int sizeX;
        private volatile int sizeY;

        Game(ClientImpl client, Loc loc, int baseX, int baseY) {
            super(client, loc, baseX, baseY);
        }

        @Override
        public int sizeX() {
            return sized() ? sizeX : 1;
        }

        @Override
        public int sizeY() {
            return sized() ? sizeY : 1;
        }

        /**
         * Reads the definition's size the first time it is needed. A definition that can't be read isn't remembered,
         * so it is tried again next time.
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

        @Override
        public Point getSceneMinLocation() {
            return new Point(loc.coord().x(), loc.coord().z());
        }

        @Override
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
    }
}
