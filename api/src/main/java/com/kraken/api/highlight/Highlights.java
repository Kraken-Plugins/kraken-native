package com.kraken.api.highlight;

import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.internal.invoke.NativeInvoker;
import com.kraken.api.mapping.Mapping;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The client's own highlights: model outlines and fills, and tile outlines and fills, drawn inside the 3D scene with
 * its depth, following actors and the camera frame by frame. Mirrors the {@code ClientOp.highlight*} functions, plus
 * the object and object type highlights the client has no Lua function for.
 *
 * <p>Plugins take a {@link HighlightChannel} of their own ({@link #channel}), give it a style and set what it lights
 * up. The hook DLL makes the calls on the game thread, so highlights only show while the client's native hook is
 * connected ({@link #isAvailable()}). This class remembers every channel's style and targets regardless, sends only
 * the differences, and applies all of it again whenever the hook (re)connects, so a plugin can set highlights before
 * the hook is up or across a reconnect.
 *
 * <p>Each category has channels 0 to {@link #channelMax()}. When something is lit on several channels, the client
 * draws the lowest channel whose style is visible. {@link #channel(HighlightCategory)} hands out the highest free
 * channel first, leaving the low ones to whoever wants precedence.
 *
 * <p>Thread-safe.
 */
public final class Highlights {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The highest channel when the mapping doesn't say.
     */
    private static final int DEFAULT_CHANNEL_MAX = 31;

    /**
     * The bytes reserved for a {@code HighlightConfig}: its fields end before this.
     */
    private static final int CONFIG_BYTES = 0x20;

    private final int manager;
    private final int channelMax;
    private final Map<HighlightCategory, Integer> categoryIds = new EnumMap<>(HighlightCategory.class);
    private final Map<String, Long> functions = new HashMap<>();
    private final ConfigLayout configLayout;
    private final int stringSize;
    private final int ssoCapacity;
    private final int heapFlagByte;

    private final Map<ChannelKey, HighlightChannel> channels = new LinkedHashMap<>();
    private NativeInvoker invoker;
    private boolean sendFailed;

    /**
     * Channels released while their clear couldn't reach the hook. The client may still show what they lit, so they
     * are cleared when the hook next connects.
     */
    private final Set<ChannelKey> unsentClears = new LinkedHashSet<>();

    /**
     * Resolves the highlight functions and layouts once.
     *
     * @param mapping the client build's mapping
     */
    public Highlights(Mapping mapping) {
        this.manager = mapping.has("Client.highlightManager") ? mapping.offset("Client.highlightManager") : -1;
        this.channelMax = mapping.offsetOr("HighlightManager.channelMax", DEFAULT_CHANNEL_MAX);
        for (HighlightCategory category : HighlightCategory.values()) {
            categoryIds.put(category, mapping.enumValue("highlightCategory", category.luaName(), category.defaultId()));
            resolve(mapping, category.onFunction());
            resolve(mapping, category.offFunction());
        }
        resolve(mapping, "highlightChannelConfigure");
        resolve(mapping, "highlightChannelClear");
        this.configLayout = ConfigLayout.of(mapping);
        this.stringSize = containerOr(mapping, "string.size", 24);
        this.ssoCapacity = containerOr(mapping, "string.ssoCapacity", 23);
        this.heapFlagByte = containerOr(mapping, "string.heapFlagByte", 23);
    }

    private void resolve(Mapping mapping, String key) {
        if (mapping.hasFunction(key)) {
            functions.put(key, mapping.function(key));
        }
    }

    private static int containerOr(Mapping mapping, String key, int fallback) {
        try {
            return mapping.container(key);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Whether highlights show now: the hook is connected and the mapping has the highlight store.
     *
     * @return true while calls reach the client
     */
    public synchronized boolean isAvailable() {
        return invoker != null && mapped();
    }

    /**
     * Whether this build's mapping has what a category needs: its on and off functions, the style function and the
     * highlight store. A category the updater couldn't map is never lit, whatever its channels hold.
     *
     * @param category the category
     * @return true if the category can be drawn once the hook connects
     */
    public boolean isSupported(HighlightCategory category) {
        return mapped() && functions.containsKey(category.onFunction()) && functions.containsKey(category.offFunction());
    }

    /**
     * Whether the mapping has the highlight store and the style function, without which nothing is sent.
     *
     * @return true if calls can be built
     */
    private boolean mapped() {
        return manager >= 0 && configLayout != null && functions.containsKey("highlightChannelConfigure");
    }

    /**
     * The highest channel number in every category.
     *
     * @return the last valid channel, 31 in build 241.3
     */
    public int channelMax() {
        return channelMax;
    }

    /**
     * Takes the highest free channel of a category, styled with {@link HighlightConfig#DEFAULT} until configured.
     *
     * @param category the category
     * @return the channel, the caller's until it {@linkplain HighlightChannel#release releases} it
     * @throws IllegalStateException if every channel of the category is taken
     */
    public synchronized HighlightChannel channel(HighlightCategory category) {
        for (int channel = channelMax; channel >= 0; channel--) {
            if (!channels.containsKey(new ChannelKey(category, channel))) {
                return claim(category, channel);
            }
        }
        throw new IllegalStateException("every " + category + " highlight channel is taken");
    }

    /**
     * Takes a given channel of a category, for a caller that wants a particular precedence.
     *
     * @param category the category
     * @param channel  the channel, 0 to {@link #channelMax()}
     * @return the channel, the caller's until it releases it
     * @throws IllegalArgumentException if the channel is out of range
     * @throws IllegalStateException    if someone else holds it
     */
    public synchronized HighlightChannel channel(HighlightCategory category, int channel) {
        if (channel < 0 || channel > channelMax) {
            throw new IllegalArgumentException("channel must be 0-" + channelMax + ", got " + channel);
        }
        if (channels.containsKey(new ChannelKey(category, channel))) {
            throw new IllegalStateException(category + " highlight channel " + channel + " is taken");
        }
        return claim(category, channel);
    }

    private HighlightChannel claim(HighlightCategory category, int channel) {
        HighlightChannel claimed = new HighlightChannel(this, Objects.requireNonNull(category), channel);
        channels.put(new ChannelKey(category, channel), claimed);
        send(List.of(configureCall(category, channel, claimed.config())));
        return claimed;
    }

    /**
     * Uses a new connection to the hook for the calls, and applies every channel's style and targets through it.
     * Internal: the client's native hook calls this when its connection comes up, and with null when it drops.
     *
     * @param invoker the connection, or null while there is none
     */
    public void setInvoker(NativeInvoker invoker) {
        synchronized (this) {
            this.invoker = invoker;
            this.sendFailed = false;
            if (invoker == null) {
                return;
            }
            List<NativeCall> calls = new ArrayList<>();
            for (ChannelKey released : unsentClears) {
                calls.add(clearCall(released.category(), released.channel()));
            }
            for (HighlightChannel channel : channels.values()) {
                // The client may still hold what this channel lit before the connection dropped
                calls.add(clearCall(channel.getCategory(), channel.getChannel()));
                calls.add(configureCall(channel.getCategory(), channel.getChannel(), channel.config()));
                for (HighlightTarget target : channel.targets()) {
                    calls.add(onCall(target, channel.getChannel()));
                }
            }
            if (send(calls)) {
                unsentClears.clear();
            }
        }
    }

    /**
     * Turns off everything lit on every channel and drops the channels. The client calls this as it shuts down.
     */
    public synchronized void releaseAll() {
        for (HighlightChannel channel : List.copyOf(channels.values())) {
            channel.release();
        }
    }

    synchronized void configure(HighlightChannel channel, HighlightConfig config) {
        send(List.of(configureCall(channel.getCategory(), channel.getChannel(), config)));
    }

    synchronized void update(HighlightChannel channel, Collection<HighlightTarget> off, Collection<HighlightTarget> on) {
        List<NativeCall> calls = new ArrayList<>(off.size() + on.size());
        for (HighlightTarget target : off) {
            calls.add(offCall(target, channel.getChannel()));
        }
        for (HighlightTarget target : on) {
            calls.add(onCall(target, channel.getChannel()));
        }
        send(calls);
    }

    synchronized void clear(HighlightChannel channel) {
        send(List.of(clearCall(channel.getCategory(), channel.getChannel())));
    }

    synchronized void release(HighlightChannel channel) {
        ChannelKey key = new ChannelKey(channel.getCategory(), channel.getChannel());
        if (channels.remove(key) == channel && !send(List.of(clearCall(channel.getCategory(), channel.getChannel())))) {
            unsentClears.add(key);
        }
    }

    /**
     * Sends calls if the hook is connected. Calls the mapping can't build (null) are left out; a broken connection is
     * logged once, and the state is applied again when the hook reconnects.
     *
     * @param calls the calls, null entries skipped
     * @return false if the calls couldn't reach the hook: none is connected, or the connection broke
     */
    private boolean send(List<NativeCall> calls) {
        NativeInvoker target = invoker;
        if (target == null) {
            return false;
        }
        if (!mapped()) {
            return true;
        }
        List<NativeCall> batch = calls.stream().filter(Objects::nonNull).toList();
        if (batch.isEmpty()) {
            return true;
        }
        try {
            target.invoke(batch);
            return true;
        } catch (RuntimeException e) {
            if (!sendFailed) {
                sendFailed = true;
                log.log(Level.WARNING, "Could not send highlights to the native hook", e);
            }
            return false;
        }
    }

    /**
     * The call that styles a channel: {@code highlightChannelConfigure(nullptr, category, channel, &config)}.
     *
     * @param category the category
     * @param channel  the channel
     * @param config   the style
     * @return the call, or null if the mapping lacks it
     */
    NativeCall configureCall(HighlightCategory category, int channel, HighlightConfig config) {
        Long rva = functions.get("highlightChannelConfigure");
        if (rva == null || configLayout == null) {
            return null;
        }
        return NativeCall.to(rva).imm(0).imm(categoryIds.get(category)).imm(channel).data(configLayout.encode(config))
                .build();
    }

    /**
     * The call that drops everything lit on a channel: {@code highlightChannelClear(mgr, category, channel)}.
     *
     * @param category the category
     * @param channel  the channel
     * @return the call, or null if the mapping lacks it
     */
    NativeCall clearCall(HighlightCategory category, int channel) {
        Long rva = functions.get("highlightChannelClear");
        if (rva == null || manager < 0) {
            return null;
        }
        return NativeCall.to(rva).client(manager).imm(categoryIds.get(category)).imm(channel).build();
    }

    /**
     * The call that lights a target on a channel.
     *
     * @param target  the target
     * @param channel the channel
     * @return the call, or null if the mapping lacks it
     */
    NativeCall onCall(HighlightTarget target, int channel) {
        return targetCall(target, channel, true);
    }

    /**
     * The call that turns a target off on a channel.
     *
     * @param target  the target
     * @param channel the channel
     * @return the call, or null if the mapping lacks it
     */
    NativeCall offCall(HighlightTarget target, int channel) {
        return targetCall(target, channel, false);
    }

    /**
     * Builds an on or off call. The functions take the highlight store first, then the target's key, then the
     * channel; the on functions of the type categories and of players take a trailing false, and the loc, obj and tile
     * functions a {@code useSourceCoord} of false, as the Lua functions pass them.
     *
     * @param target  the target
     * @param channel the channel
     * @param on      true to light it, false to turn it off
     * @return the call, or null if the mapping lacks it
     */
    private NativeCall targetCall(HighlightTarget target, int channel, boolean on) {
        HighlightCategory category = target.category();
        Long rva = functions.get(on ? category.onFunction() : category.offFunction());
        if (rva == null || manager < 0) {
            return null;
        }
        NativeCall.Builder call = NativeCall.to(rva).client(manager);
        switch (target) {
            case HighlightTarget.Npc npc -> call.imm(npc.index()).imm(npc.creationCycle()).imm(channel);
            case HighlightTarget.NpcType type -> typeArgs(call, type.id(), channel, on);
            case HighlightTarget.LocType type -> typeArgs(call, type.id(), channel, on);
            case HighlightTarget.ObjType type -> typeArgs(call, type.id(), channel, on);
            case HighlightTarget.Player player -> {
                byte[] name = playerName(player.name());
                if (name == null) {
                    return null;
                }
                call.data(name).imm(channel);
                if (on) {
                    call.imm(0);
                }
            }
            case HighlightTarget.Tile tile -> call.data(coord(tile.location())).imm(channel).imm(0);
            case HighlightTarget.Loc loc -> call.imm(loc.id()).data(coord(loc.location())).imm(channel).imm(0);
            case HighlightTarget.Obj obj -> call.imm(obj.id()).data(coord(obj.location())).imm(channel).imm(0);
        }
        return call.build();
    }

    private static void typeArgs(NativeCall.Builder call, int id, int channel, boolean on) {
        call.imm(id).imm(channel);
        if (on) {
            call.imm(0);
        }
    }

    /**
     * A {@code CoordGrid}: {@code {i32 level, i32 x, i32 z}} in world tiles, z being north.
     *
     * @param location the world tile
     * @return the 12 bytes
     */
    private static byte[] coord(WorldPoint location) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(location.getPlane()).putInt(location.getX()).putInt(location.getY()).array();
    }

    /**
     * A display name as the client's short string: the UTF-8 bytes inline, NUL-terminated, with the byte after the
     * buffer holding the spare capacity.
     *
     * @param name the display name
     * @return the string's bytes, or null if the name is too long for the short form (no display name is)
     */
    private byte[] playerName(String name) {
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > ssoCapacity || heapFlagByte >= stringSize) {
            return null;
        }
        byte[] string = new byte[stringSize];
        System.arraycopy(utf8, 0, string, 0, utf8.length);
        string[heapFlagByte] = (byte) (ssoCapacity - utf8.length);
        return string;
    }

    /**
     * One channel of one category.
     *
     * @param category the category
     * @param channel  the channel
     */
    private record ChannelKey(HighlightCategory category, int channel) {
    }

    /**
     * Where {@code highlightChannelConfigure} reads each field of its {@code HighlightConfig}.
     *
     * @param colour             the {@code {r, g, b, a}} bytes
     * @param outlineWidth       the u16 width
     * @param enableModelOutline the bool
     * @param enableTileOutline  the bool
     * @param enableModelFill    the bool
     * @param enableTileFill     the bool
     * @param alwaysOnTop        the bool
     * @param showInsideEdges    the bool
     * @param saveToFile         the bool
     * @param snapToGrid         the bool
     * @param swOnly             the bool
     * @param ignoreRebuild      the bool
     * @param disableModel       the bool
     */
    private record ConfigLayout(int colour, int outlineWidth, int enableModelOutline, int enableTileOutline,
                                int enableModelFill, int enableTileFill, int alwaysOnTop, int showInsideEdges,
                                int saveToFile, int snapToGrid, int swOnly, int ignoreRebuild, int disableModel) {

        /**
         * The layout in a mapping.
         *
         * @param mapping the mapping
         * @return the layout, or null if the mapping lacks a field or one falls outside the reserved bytes
         */
        static ConfigLayout of(Mapping mapping) {
            String[] fields = {"colour", "outlineWidth", "enableModelOutline", "enableTileOutline", "enableModelFill",
                    "enableTileFill", "alwaysOnTop", "showInsideEdges", "saveToFile", "snapToGrid", "swOnly",
                    "ignoreRebuild", "disableModel"};
            int[] offsets = new int[fields.length];
            for (int i = 0; i < fields.length; i++) {
                String key = "HighlightConfig." + fields[i];
                if (!mapping.has(key)) {
                    return null;
                }
                offsets[i] = mapping.offset(key);
                if (offsets[i] < 0 || offsets[i] + 4 > CONFIG_BYTES) {
                    return null;
                }
            }
            return new ConfigLayout(offsets[0], offsets[1], offsets[2], offsets[3], offsets[4], offsets[5],
                    offsets[6], offsets[7], offsets[8], offsets[9], offsets[10], offsets[11], offsets[12]);
        }

        /**
         * Lays a config out as the client reads it.
         *
         * @param config the config
         * @return the struct's bytes
         */
        byte[] encode(HighlightConfig config) {
            ByteBuffer buf = ByteBuffer.allocate(CONFIG_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            Color c = config.highlightColour();
            buf.put(colour, (byte) c.getRed());
            buf.put(colour + 1, (byte) c.getGreen());
            buf.put(colour + 2, (byte) c.getBlue());
            buf.put(colour + 3, (byte) c.getAlpha());
            buf.putShort(outlineWidth, (short) config.outlineWidth());
            put(buf, enableModelOutline, config.enableModelOutline());
            put(buf, enableTileOutline, config.enableTileOutline());
            put(buf, enableModelFill, config.enableModelFill());
            put(buf, enableTileFill, config.enableTileFill());
            put(buf, alwaysOnTop, config.alwaysOnTop());
            put(buf, showInsideEdges, config.showInsideEdges());
            put(buf, saveToFile, config.saveToFile());
            put(buf, snapToGrid, config.snapToGrid());
            put(buf, swOnly, config.swOnly());
            put(buf, ignoreRebuild, config.ignoreRebuild());
            put(buf, disableModel, config.disableModel());
            return buf.array();
        }

        private static void put(ByteBuffer buf, int offset, boolean value) {
            buf.put(offset, (byte) (value ? 1 : 0));
        }
    }

    /**
     * The channels held now, for diagnostics.
     *
     * @return each held channel's category and number, in the order they were taken
     */
    public synchronized Map<HighlightCategory, Set<Integer>> heldChannels() {
        Map<HighlightCategory, Set<Integer>> out = new EnumMap<>(HighlightCategory.class);
        for (ChannelKey key : channels.keySet()) {
            out.computeIfAbsent(key.category(), k -> new LinkedHashSet<>()).add(key.channel());
        }
        return out;
    }
}
