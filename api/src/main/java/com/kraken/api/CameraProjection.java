package com.kraken.api;

/**
 * One frame's camera: turns points of the top-level scene into canvas positions, replaying the float arithmetic of the
 * client's Lua {@code Graphics.worldToScreenCoord} handler step by step. It is read with
 * {@link Client#getCameraProjection()}. {@link Perspective} reads one per call; code that projects many points for the
 * same frame can read one and reuse it, saving the camera reads each {@code Perspective} call makes.
 *
 * <p>It's a snapshot of the camera's eye, the sines and cosines of its pitch and yaw (as the scene keeps them for the
 * frame), the scale, the viewport's centre and the window-to-canvas pixel ratio: once the camera moves, a new one is
 * needed.
 */
public final class CameraProjection {

    private static final float HALF = 0.5f;

    private final int eyeX;
    private final int eyeY;
    private final int eyeZ;
    private final float sinPitch;
    private final float cosPitch;
    private final float sinYaw;
    private final float cosYaw;
    private final int scale;
    private final int centerX;
    private final int centerY;
    private final float scaleX;
    private final float scaleY;

    /**
     * One frame's projection, in the client's own axes (height second, north third).
     *
     * @param eyeX     the camera eye's local x
     * @param eyeY     the camera eye's height
     * @param eyeZ     the camera eye's local y (north)
     * @param sinPitch sine of the pitch, as the scene stores it
     * @param cosPitch cosine of the pitch
     * @param sinYaw   sine of the yaw
     * @param cosYaw   cosine of the yaw
     * @param scale    the projection scale ({@link Client#getScale})
     * @param centerX  the viewport's centre x plus its x offset, in window pixels
     * @param centerY  the viewport's centre y plus its y offset, in window pixels
     * @param scaleX   canvas width over window width
     * @param scaleY   canvas height over window height
     */
    CameraProjection(int eyeX, int eyeY, int eyeZ, float sinPitch, float cosPitch, float sinYaw, float cosYaw,
                     int scale, int centerX, int centerY, float scaleX, float scaleY) {
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.sinPitch = sinPitch;
        this.cosPitch = cosPitch;
        this.sinYaw = sinYaw;
        this.cosYaw = cosYaw;
        this.scale = scale;
        this.centerX = centerX;
        this.centerY = centerY;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    /**
     * Projects a point of the top-level scene: taken relative to the camera's eye, rotated by the camera's yaw and
     * pitch, divided by its depth and multiplied by the scale, then offset to the viewport's centre and rescaled from
     * window pixels to canvas pixels.
     *
     * <p>Differs from the client on purpose: a point at or behind the camera (depth &lt;= 0) has no canvas position,
     * and the client returns a meaningless one; this returns null.
     *
     * @param x the local x (east, 128 per tile)
     * @param y the local y (north)
     * @param z the height (more negative is higher), as {@link Perspective#getTileHeight} returns it
     * @return the canvas position in the client's pixels (it may be off the canvas), or null if the point is at or
     *         behind the camera
     */
    public Point project(int x, int y, int z) {
        float dx = x - eyeX;
        float dy = z - eyeY;
        float dz = y - eyeZ;
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
        return new Point(round(scaleX * screenX), round(scaleY * screenY));
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
     * The window-to-canvas rescale's rounding: half away from zero, then truncated.
     *
     * @param value the rescaled coordinate
     * @return the rounded coordinate as the client stores it
     */
    private static int round(float value) {
        return truncate(value < 0 ? value - HALF : value + HALF);
    }
}
