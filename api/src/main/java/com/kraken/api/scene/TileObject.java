package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.action.ActionResult;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.Loc;
import com.kraken.api.internal.MapCoord;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.Rectangle2D;
import java.util.Objects;

import static com.kraken.api.Perspective.LOCAL_COORD_BITS;
import static com.kraken.api.Perspective.LOCAL_HALF_TILE_SIZE;

/**
 * An object (scenery) on a tile: a {@link WallObject}, {@link DecorativeObject}, {@link GameObject} or
 * {@link GroundObject}. Its name and actions come from {@link Client#getObjectDefinition} by {@link #getId()}.
 *
 * <p>A value: it holds the object's id, slot and tile as one read of the scene found them, with the map base of that
 * scene. Two reads of the same object in the same scene give equal objects, so a {@code Set<TileObject>} filled from
 * spawn events can be emptied by the despawn events. The canvas helpers read the camera each call.
 */
public abstract sealed class TileObject permits WallObject, DecorativeObject, GameObject, GroundObject {

    /**
     * The bit {@link #getHash()} keeps the plane at.
     */
    public static final int HASH_PLANE_SHIFT = 14;

    /**
     * The tag type for an object, at bits 16-18 of {@link #getHash()} (the native tag uses the same layout).
     */
    private static final long HASH_TYPE_OBJECT = 2;

    final Client client;
    final Loc loc;
    final int baseX;
    final int baseY;

    /**
     * An object as the scene listing found it.
     *
     * @param client the client
     * @param loc    the object, as the listing returns it
     * @param baseX  the map base x the scene was read with
     * @param baseY  the map base y
     */
    TileObject(Client client, Loc loc, int baseX, int baseY) {
        this.client = client;
        this.loc = loc;
        this.baseX = baseX;
        this.baseY = baseY;
    }

    /**
     * The object for a listed loc's layer.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param client the client
     * @param loc    the loc, as the scene listing returns it
     * @param baseX  the map base x the scene was read with
     * @param baseY  the map base y
     * @return a {@link WallObject}, {@link DecorativeObject}, {@link GameObject} or {@link GroundObject}
     */
    public static TileObject of(Client client, Loc loc, int baseX, int baseY) {
        return switch (loc.layer()) {
            case Loc.WALL -> new WallObject(client, loc, baseX, baseY);
            case Loc.WALL_DECOR -> new DecorativeObject(client, loc, baseX, baseY);
            case Loc.GAME_OBJECT -> new GameObject(client, loc, baseX, baseY);
            default -> new GroundObject(client, loc, baseX, baseY);
        };
    }

    /**
     * A key for the object: scene x in bits 0-6, scene y in 7-13, plane in 14-15, the tag type (2, an object) in
     * 16-18, the id in 20-51 and the world view (12 bits) in 52-63. Built from the object's fields rather than read:
     * bit 19 (set for some walls) isn't read, so it is 0.
     *
     * @return the hash
     */
    public long getHash() {
        MapCoord at = loc.coord();
        return (at.x() & 0x7f)
                | (long) (at.z() & 0x7f) << 7
                | (long) (at.level() & 3) << HASH_PLANE_SHIFT
                | HASH_TYPE_OBJECT << 16
                | (loc.id() & 0xffffffffL) << 20
                | (long) (WorldView.TOPLEVEL & 0xfff) << 52;
    }

    /**
     * The object's x in local units: its tile's centre, or for a game object the centre of its footprint.
     *
     * @return the local x
     */
    public int getX() {
        return (loc.coord().x() << LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE;
    }

    /**
     * The object's y in local units.
     *
     * @return the local y
     */
    public int getY() {
        return (loc.coord().z() << LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE;
    }

    /**
     * The height of the ground at the object's local location.
     *
     * @return the height (more negative is higher); 0 if it can't be read
     */
    public int getZ() {
        return getWorldView().getTileHeight(getX(), getY(), getPlane());
    }

    /**
     * The plane of the tile the object is on.
     *
     * @return the plane, 0-3
     */
    public int getPlane() {
        return loc.coord().level();
    }

    /**
     * The world view the object is in.
     *
     * @return the top-level world view
     */
    public WorldView getWorldView() {
        return client.getTopLevelWorldView();
    }

    /**
     * The object's id.
     *
     * @return the object (loc type) id, as in {@code gameval.ObjectID}
     */
    public int getId() {
        return loc.id();
    }

    /**
     * The object's name as the menu shows it: its impostor's ({@code ObjectComposition.getImpostor()}), picked by its
     * varbit or varp.
     *
     * @return the name; null if the object shows as nothing or its definition can't be read
     */
    public String getName() {
        return client.getInteractions().name(this);
    }

    /**
     * The options the object's menu offers, which {@link #interact(String)} matches against: its impostor's actions,
     * with conditional actions resolved against the current vars.
     *
     * @return the options by op, index 0 being op 1, null entries for empty slots; null if the object shows as nothing
     *         or its definition can't be read
     */
    public String[] getActions() {
        return client.getInteractions().actions(this);
    }

    /**
     * Runs one of the object's options, as clicking it in the menu does: the row names the object's own id and the
     * tile it was placed on, on its own plane. The option is matched loosely: markup and case are ignored, and
     * spaces, {@code -} and {@code _} count as the same, so "chop-down" matches "Chop down".
     *
     * @param option the option's text, such as "Chop down"
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#GONE} once its tile no longer
     *         holds it or it shows as nothing, {@link ActionResult#NO_SUCH_OPTION} if it has no such option
     * @throws NullPointerException if the option is null
     */
    public ActionResult interact(String option) {
        return client.getInteractions().interact(this, option);
    }

    /**
     * Runs one of the object's options by its number, as the menu numbers them.
     *
     * @param op the op, 1-5: index + 1 in {@link #getActions()}
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} for a number
     *         out of range or an empty slot
     */
    public ActionResult interact(int op) {
        return client.getInteractions().interact(this, op);
    }

    /**
     * The object's world location: the tile it was placed on. {@link GameObject} overrides this to report its
     * footprint's centre.
     *
     * @return the world tile
     */
    public WorldPoint getWorldLocation() {
        return new WorldPoint(baseX + loc.coord().x(), baseY + loc.coord().z(), getPlane());
    }

    /**
     * The object's centre in local units.
     *
     * @return {@link #getX()}, {@link #getY()} in the object's world view
     */
    public LocalPoint getLocalLocation() {
        return new LocalPoint(getX(), getY(), WorldView.TOPLEVEL);
    }

    /**
     * The tile the object is on (a game object's south-west tile).
     *
     * @return a handle on the tile in the scene the object was read from
     */
    public Tile getTile() {
        return Tile.at(client, loc.coord(), baseX, baseY);
    }

    /**
     * Where the object's centre appears on the canvas, on the ground.
     *
     * @return the canvas position, or null if it is outside the scene or behind the camera
     */
    public Point getCanvasLocation() {
        return getCanvasLocation(0);
    }

    /**
     * Where a point above the object's centre appears on the canvas.
     *
     * @param zOffset how far above the ground, in local units
     * @return the canvas position, or null if it is outside the scene or behind the camera
     */
    public Point getCanvasLocation(int zOffset) {
        return Perspective.localToCanvas(client, getLocalLocation(), getPlane(), zOffset);
    }

    /**
     * The outline of the tile the object is on, or for a game object the tiles it covers.
     *
     * @return the outline in canvas pixels, or null if it is outside the scene or a corner is behind the camera
     */
    public Polygon getCanvasTilePoly() {
        return Perspective.getCanvasTileAreaPoly(client, getLocalLocation(), 1, 1, getPlane(), 0);
    }

    /**
     * Where to draw text centred over the object, as {@link Perspective#getCanvasTextLocation} places it but on the
     * object's own plane rather than its world view's.
     *
     * @param graphics the graphics the text will be drawn with (its font sets the width)
     * @param text     the text
     * @param zOffset  how far above the ground, in local units
     * @return the baseline's left end in canvas pixels, or null if the text is null, or the object is outside the
     *         scene or behind the camera
     */
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
        return other != null && other.getClass() == getClass() && loc.equals(((TileObject) other).loc)
                && baseX == ((TileObject) other).baseX && baseY == ((TileObject) other).baseY;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), loc, baseX, baseY);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + loc.id() + " at " + getWorldLocation() + "]";
    }
}
