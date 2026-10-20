package com.kraken.api.lua;

import com.kraken.api.lua.events.Events;
import com.kraken.api.lua.types.ConfigTypes;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

/**
 * The Lua mirror: one object per namespace of the client's Lua {@code osrs} table, each reproducing what its native
 * handlers read. {@link #clientOp()} mirrors {@code osrs.ClientOp}, {@link #clientState()} {@code osrs.ClientState},
 * {@link #events()} {@code osrs.Events}, {@link #engine()} {@code osrs.Engine}, {@link #camera()} and
 * {@link #graphics()} the getters of {@code Camera} and {@code Graphics}, {@link #world()} {@code osrs.World},
 * {@link #collision()} {@code osrs.Collision} and {@link #interfaces()} {@code InterfaceQuery} (the
 * {@code osrs.IfType} functions). {@link #scene()} and {@link #configs()} are Java extras.
 *
 * <p>This is the low-level layer: the RuneLite-shaped API ({@code KrakenApi.client()}) is built on it, and the dev
 * tools use it directly to show what the Lua API reads. Get it from {@code KrakenApi.lua()}.
 */
public final class LuaApi {

    private final ConfigTypes configs;
    private final ClientOp clientOp;
    private final ClientState clientState;
    private final Engine engine;
    private final Camera camera;
    private final Graphics graphics;
    private final World world;
    private final Collision collision;
    private final Scene scene;
    private final InterfaceQuery interfaces;
    private final Events events = new Events();

    /**
     * Builds every namespace over one process image, sharing one config type cache.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     */
    public LuaApi(Memory memory, Mapping mapping) {
        this.configs = new ConfigTypes(memory, mapping);
        this.clientOp = new ClientOp(memory, mapping, configs);
        this.clientState = new ClientState(memory, mapping, configs);
        this.engine = new Engine(clientOp);
        this.camera = new Camera(memory, mapping);
        this.graphics = new Graphics(memory, mapping);
        this.world = new World(clientOp);
        this.collision = new Collision(clientOp);
        this.scene = new Scene(clientOp);
        this.interfaces = new InterfaceQuery(memory, mapping);
    }

    /**
     * Mirrors {@code osrs.ClientOp}.
     *
     * @return the reader for players, NPCs, inventories and item definitions
     */
    public ClientOp clientOp() {
        return clientOp;
    }

    /**
     * Mirrors {@code osrs.ClientState}.
     *
     * @return the reader for stats, vars, the tick, login state and profile type
     */
    public ClientState clientState() {
        return clientState;
    }

    /**
     * Mirrors {@code osrs.Engine}'s config decoders.
     *
     * @return the decoder for item, NPC and loc definitions by id
     */
    public Engine engine() {
        return engine;
    }

    /**
     * Mirrors the getters of Lua's {@code Camera}.
     *
     * @return the reader for the camera's position, look-at point and angles
     */
    public Camera camera() {
        return camera;
    }

    /**
     * Mirrors the getters of Lua's {@code Graphics}.
     *
     * @return the reader for the client's size and the world-to-screen projection
     */
    public Graphics graphics() {
        return graphics;
    }

    /**
     * Mirrors {@code osrs.World}.
     *
     * @return the reader for the scene's collision maps
     */
    public World world() {
        return world;
    }

    /**
     * Mirrors {@code osrs.Collision}.
     *
     * @return the live collision checks
     */
    public Collision collision() {
        return collision;
    }

    /**
     * The top-level scene's locs, ground items, base and ground heights. Not a Lua namespace.
     *
     * @return the scene reader
     */
    public Scene scene() {
        return scene;
    }

    /**
     * Mirrors {@code InterfaceQuery}, the functions the Lua docs list under {@code osrs.IfType}.
     *
     * @return the reader for interface components and groups
     */
    public InterfaceQuery interfaces() {
        return interfaces;
    }

    /**
     * Mirrors {@code osrs.screenSize}: the game window's size in physical pixels.
     *
     * @return {@code x} = width, {@code y} = height; null if it can't be read
     */
    public Vector2 screenSize() {
        return graphics.screenSize();
    }

    /**
     * The config types (definitions) every reader shares, with their cache. Not a Lua namespace.
     *
     * @return the shared config type reader
     */
    public ConfigTypes configs() {
        return configs;
    }

    /**
     * Mirrors {@code osrs.Events}. Events are only synthesised while {@code KrakenApi.start()}'s poll loop runs.
     *
     * @return the Lua event bus to subscribe to game events on
     */
    public Events events() {
        return events;
    }
}
