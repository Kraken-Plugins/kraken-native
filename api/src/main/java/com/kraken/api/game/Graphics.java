package com.kraken.api.game;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

import java.nio.ByteBuffer;

/**
 * Mirrors the getters of the client's Lua {@code Graphics} namespace: the client's size and the world-to-screen
 * projection. Its shape functions ({@code addShape}, {@code removeShape}, {@code clearAllShapes}) draw into the client,
 * so they belong to the in-process backend, not this reader.
 *
 * <p>Screen positions are in the units {@link #clientDimensions()} measures: the client's own (DPI-scaled) pixels,
 * with (0, 0) at the top-left of the game. The window's physical pixels are {@link #screenSize()}.
 */
public final class Graphics extends GameReader {

    private static final float HALF = 0.5f;

    private final int cRenderer;
    private final int cTopWorld;
    private final int cPos;
    private final int cScale;
    private final int wScene;
    private final int scTrig;
    private final int rCanvasWidth;
    private final int rCanvasHeight;
    private final int rScreenWidth;
    private final int rScreenHeight;
    private final int rViewport;
    private final int vCenterX;
    private final int vCenterY;
    private final int vOffsetX;
    private final int vOffsetY;
    private final boolean canProject;

    public Graphics(Memory memory, Mapping mapping) {
        super(memory, mapping);
        this.cRenderer = mapping.offset("Client.renderer");
        this.cTopWorld = mapping.offset("Client.topLevelWorld");
        this.cPos = mapping.offset("Client.camera.pos");
        this.cScale = optionalOffset("Client.zoomA");
        this.wScene = optionalOffset("World.scene");
        this.scTrig = optionalOffset("Scene.cameraTrig");
        this.rCanvasWidth = mapping.offset("Renderer.canvasWidth");
        this.rCanvasHeight = mapping.offset("Renderer.canvasHeight");
        this.rScreenWidth = optionalOffset("Renderer.screenWidth");
        this.rScreenHeight = optionalOffset("Renderer.screenHeight");
        this.rViewport = optionalOffset("Renderer.viewport");
        this.vCenterX = optionalOffset("Viewport.centerX");
        this.vCenterY = optionalOffset("Viewport.centerY");
        this.vOffsetX = optionalOffset("Viewport.offsetX");
        this.vOffsetY = optionalOffset("Viewport.offsetY");
        this.canProject = cScale >= 0 && wScene >= 0 && scTrig >= 0 && rScreenWidth >= 0 && rScreenHeight >= 0
                && rViewport >= 0 && vCenterX >= 0 && vCenterY >= 0 && vOffsetX >= 0 && vOffsetY >= 0;
    }

    /**
     * Mirrors {@code Graphics.clientDimensions}: the client's width and height in its own (DPI-scaled) pixels, the
     * units {@link #worldToScreenCoord} returns.
     *
     * @return {@code x} = width, {@code y} = height; null if the client can't be read
     */
    public Vector2 clientDimensions() {
        long renderer = renderer();
        if (renderer == 0) {
            return null;
        }
        int width = safeInt(renderer + rCanvasWidth, -1);
        int height = safeInt(renderer + rCanvasHeight, -1);
        return width < 0 || height < 0 ? null : new Vector2(width, height);
    }

    /**
     * Mirrors {@code osrs.screenSize}: the game window's size in physical pixels. With display scaling above 100% this
     * is larger than {@link #clientDimensions()}.
     *
     * @return {@code x} = width, {@code y} = height; null if the client can't be read or the mapping doesn't have it
     */
    public Vector2 screenSize() {
        long renderer = renderer();
        if (renderer == 0 || rScreenWidth < 0 || rScreenHeight < 0) {
            return null;
        }
        int width = safeInt(renderer + rScreenWidth, -1);
        int height = safeInt(renderer + rScreenHeight, -1);
        return width < 0 || height < 0 ? null : new Vector2(width, height);
    }

    /**
     * Mirrors {@code Graphics.worldToScreenCoord}: where a point of the top-level scene appears on screen, worked out
     * the way the client's handler does it. The point is taken relative to the camera's eye, rotated by the camera's
     * yaw and pitch (the sines and cosines the scene keeps for the frame), divided by its depth and multiplied by the
     * projection scale, then offset to the viewport's centre and rescaled from window pixels to client pixels.
     *
     * <p>Differs from the client on purpose: a point at or behind the camera (depth &lt;= 0) has no screen position,
     * and the client returns a meaningless one; this returns null.
     *
     * @param coord a scene fine coordinate, e.g. from {@link Client#coordFine()}; its {@code y} is the height (subtract
     *              from it to go up, as the Lua example does to reach above a player's head)
     * @return the screen position in client pixels (may be off-screen), or null behind the camera or if it can't be
     *         read
     */
    public Vector2 worldToScreenCoord(FineCoord coord) {
        if (coord == null || !canProject) {
            return null;
        }
        Projection projection = projection();
        return projection == null ? null : projection.project(coord.x(), coord.y(), coord.z());
    }

    /**
     * Reads everything {@link #worldToScreenCoord} needs once, so many points can be projected for the same frame
     * without reading the camera again. Not a Lua function.
     *
     * @return the current projection, or null if the client can't be read or the mapping lacks a projection key
     */
    public Projection projection() {
        if (!canProject) {
            return null;
        }
        long client = clientBase();
        long renderer = renderer();
        long world = client == 0 ? 0 : safePointer(client + cTopWorld);
        long scene = world == 0 ? 0 : safePointer(world + wScene);
        if (renderer == 0 || scene == 0) {
            return null;
        }
        int[] eye = readInts(client + cPos, 3);
        ByteBuffer trig = readBlock(scene + scTrig, 16);
        if (eye == null || trig == null) {
            return null;
        }
        long viewport = renderer + rViewport;
        int scale = safeInt(client + cScale, 0);
        int centerX = safeInt(viewport + vCenterX, 0) + safeInt(viewport + vOffsetX, 0);
        int centerY = safeInt(viewport + vCenterY, 0) + safeInt(viewport + vOffsetY, 0);
        int canvasWidth = safeInt(renderer + rCanvasWidth, 0);
        int canvasHeight = safeInt(renderer + rCanvasHeight, 0);
        int screenWidth = safeInt(renderer + rScreenWidth, 0);
        int screenHeight = safeInt(renderer + rScreenHeight, 0);
        if (screenWidth <= 0 || screenHeight <= 0) {
            return null;
        }
        return new Projection(eye[0], eye[1], eye[2], trig.getFloat(0), trig.getFloat(4), trig.getFloat(8),
                trig.getFloat(12), scale, centerX, centerY, (float) canvasWidth / (float) screenWidth,
                (float) canvasHeight / (float) screenHeight);
    }

    private long renderer() {
        long client = clientBase();
        return client == 0 ? 0 : safePointer(client + cRenderer);
    }

    /**
     * One frame's projection: the camera's eye, the sines and cosines of its pitch and yaw, the scale, the viewport's
     * centre and the window-to-client pixel ratio. {@link #project} replays the client's float arithmetic step by step.
     *
     * @param eyeX     the camera eye's fine x
     * @param eyeY     the camera eye's height
     * @param eyeZ     the camera eye's fine z
     * @param sinPitch sine of the pitch, as the scene stores it
     * @param cosPitch cosine of the pitch
     * @param sinYaw   sine of the yaw
     * @param cosYaw   cosine of the yaw
     * @param scale    the projection scale ({@link Camera#getScale})
     * @param centerX  the viewport's centre x plus its x offset, in window pixels
     * @param centerY  the viewport's centre y plus its y offset, in window pixels
     * @param scaleX   client width over window width
     * @param scaleY   client height over window height
     */
    public record Projection(int eyeX, int eyeY, int eyeZ, float sinPitch, float cosPitch, float sinYaw, float cosYaw,
                             int scale, int centerX, int centerY, float scaleX, float scaleY) {

        /**
         * Projects one scene point with this frame's camera.
         *
         * @param x the fine x
         * @param y the height (more negative is higher)
         * @param z the fine z
         * @return the screen position in client pixels, or null if the point is at or behind the camera
         */
        public Vector2 project(int x, int y, int z) {
            float dx = x - eyeX;
            float dy = y - eyeY;
            float dz = z - eyeZ;
            float right = cosYaw * dx + sinYaw * dz;
            float forward = cosYaw * dz - sinYaw * dx;
            float down = cosPitch * dy - sinPitch * forward;
            float depth = sinPitch * dy + cosPitch * forward;
            if (!(depth > 0)) {
                return null;
            }
            float s = scale;
            int screenX = truncate(s * right / depth) + centerX;
            int screenY = truncate(s * down / depth) + centerY;
            return new Vector2(round(scaleX * screenX), round(scaleY * screenY));
        }

        /**
         * The client's float-to-int conversion (CVTTSS2SI): truncation, with the "integer indefinite" value for NaN and
         * anything out of int range.
         *
         * @param value the float
         * @return the value truncated toward zero, or {@link Integer#MIN_VALUE} if it doesn't fit
         */
        private static int truncate(float value) {
            if (Float.isNaN(value) || value >= 2147483648f || value < -2147483648f) {
                return Integer.MIN_VALUE;
            }
            return (int) value;
        }

        /**
         * The window-to-client rescale's rounding: half away from zero, then truncated.
         *
         * @param value the rescaled coordinate
         * @return the rounded coordinate as the client stores it, as a float
         */
        private static float round(float value) {
            return truncate(value < 0 ? value - HALF : value + HALF);
        }
    }
}
