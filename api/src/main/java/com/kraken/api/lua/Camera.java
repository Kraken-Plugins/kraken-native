package com.kraken.api.lua;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

/**
 * Mirrors the getters of the client's Lua {@code Camera} namespace: where the camera is, what it looks at, and its
 * angles. The setters ({@code setPos}, {@code setLookAt}, {@code setLookAtAngles}, {@code targetNpc}…) move the camera,
 * so they belong to the in-process backend, not this reader.
 *
 * <p>Positions are fine coordinates in the top-level scene (1/128 of a tile, the space {@link ClientOp#coordFine()}
 * uses), with the height in {@code y} (more negative is higher). Angles are 16384 units per circle in the client; the
 * Lua getter converts them to degrees.
 */
public final class Camera extends GameReader {

    /**
     * Radians per native angle unit, the float the client multiplies by ({@code 2π / 16384}).
     */
    static final float RADIANS_PER_UNIT = Float.intBitsToFloat(0x39c90fdb);

    /**
     * Degrees per radian as the client stores it (one ulp below the nearest float to {@code 180 / π}).
     */
    static final float DEGREES_PER_RADIAN = Float.intBitsToFloat(0x42652ee0);

    private final int cPos;
    private final int cLookAt;
    private final int cAngles;
    private final int cScale;
    private final int cZoom;
    private final long controlledRva;
    private final long targetRva;

    public Camera(Memory memory, Mapping mapping) {
        super(memory, mapping);
        this.cPos = mapping.offset("Client.camera.pos");
        this.cLookAt = optionalOffset("Client.camera.lookAt");
        this.cAngles = mapping.offset("Client.camera.angles");
        this.cScale = optionalOffset("Client.zoomA");
        this.cZoom = optionalOffset("Client.zoomB");
        this.controlledRva = optionalGlobal("cameraControlled");
        this.targetRva = optionalGlobal("cameraTarget");
    }

    /**
     * Mirrors {@code Camera.getPos}: the camera's eye, the point the scene is projected from.
     *
     * @return the eye position in scene fine coordinates (height in {@code y}), or null if the client can't be read
     */
    public FineCoord getPos() {
        return triple(cPos);
    }

    /**
     * Mirrors {@code Camera.getLookAt}: the point the camera looks at.
     *
     * @return the look-at point in scene fine coordinates (height in {@code y}), or null if the client can't be read
     *         or the mapping doesn't have it
     */
    public FineCoord getLookAt() {
        return cLookAt < 0 ? null : triple(cLookAt);
    }

    /**
     * Mirrors {@code Camera.getLookAtAngles}: pitch and yaw in degrees, converted from the client's units with the
     * same float arithmetic the client uses.
     *
     * @return {@code x} = pitch, {@code y} = yaw, in degrees; null if the client can't be read
     */
    public Vector2 getLookAtAngles() {
        long client = clientBase();
        int[] angles = client == 0 ? null : readInts(client + cAngles, 2);
        if (angles == null) {
            return null;
        }
        return new Vector2(toDegrees(angles[0]), toDegrees(angles[1]));
    }

    /**
     * Mirrors {@code Camera.isControlled}: whether a plugin has taken control of the camera ({@code setControl(true)}).
     * The flag belongs to the client's Lua plugins, so it is false unless one of them has.
     *
     * @return true if the camera is under plugin control; false otherwise or if it can't be read
     */
    public boolean isControlled() {
        return controlledRva >= 0 && safeU8(memory.moduleBase() + controlledRva, 0) != 0;
    }

    /**
     * Mirrors {@code Camera.hasTarget}: whether a plugin has pointed the camera at an NPC or player
     * ({@code targetNpc}/{@code targetPlayer}).
     *
     * @return true if a camera target is set; false otherwise or if it can't be read
     */
    public boolean hasTarget() {
        return targetRva >= 0 && safeInt(memory.moduleBase() + targetRva, -1) != -1;
    }

    /**
     * The camera's pitch in the client's units. Not a Lua function.
     *
     * @return the pitch, 16384 units per circle (RuneLite's value times 8), or -1 if the client can't be read
     */
    public int getPitch() {
        long client = clientBase();
        return client == 0 ? -1 : safeInt(client + cAngles, -1);
    }

    /**
     * The camera's yaw in the client's units. Not a Lua function.
     *
     * @return the yaw, 16384 units per circle (RuneLite's value times 8), or -1 if the client can't be read
     */
    public int getYaw() {
        long client = clientBase();
        return client == 0 ? -1 : safeInt(client + cAngles + 4L, -1);
    }

    /**
     * The zoom setting the client derives the projection scale from. Not a Lua function.
     *
     * @return the zoom, or -1 if the client can't be read or the mapping doesn't have it
     */
    public int getZoom() {
        long client = clientBase();
        return client == 0 || cZoom < 0 ? -1 : safeInt(client + cZoom, -1);
    }

    /**
     * The projection scale ({@link Graphics#worldToScreenCoord} multiplies by it): the viewport height times the zoom
     * divided by 334, worked out each frame. Not a Lua function.
     *
     * @return the scale, or -1 if the client can't be read or the mapping doesn't have it
     */
    public int getScale() {
        long client = clientBase();
        return client == 0 || cScale < 0 ? -1 : safeInt(client + cScale, -1);
    }

    /**
     * Converts a native angle to degrees the way the client's getter does.
     *
     * @param units the angle, 16384 units per circle
     * @return the angle in degrees
     */
    static float toDegrees(int units) {
        return (float) units * RADIANS_PER_UNIT * DEGREES_PER_RADIAN;
    }

    private FineCoord triple(int offset) {
        long client = clientBase();
        int[] v = client == 0 ? null : readInts(client + offset, 3);
        return v == null ? null : new FineCoord(v[0], v[1], v[2]);
    }
}
