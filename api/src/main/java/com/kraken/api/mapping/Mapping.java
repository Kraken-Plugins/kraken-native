package com.kraken.api.mapping;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One client build's offsets, loaded from {@code mappings/<version>.json} (the file the updater writes into this
 * module's resources, {@code api/src/main/resources/mappings}, so it ships on the classpath). The API
 * holds no hard-coded offsets; every struct field, global and container layout comes from here, keyed the same way
 * the updater names them:
 *
 * <pre>{
 *   "version": "241.3",
 *   "sha256": "166231c8…",
 *   "globals":    { "client": "0xe98c18", … },
 *   "structs":    { "Actor": { "pathX": "0x3f0", … }, … },
 *   "containers": { "hash_map": { "node.value": "0x10", … }, … },
 *   "enums":      { "gameState": { "LOGGED_IN": 30 }, … },
 *   "functions":  { "worldToScreen": "0x21eb80", … }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #offset(String)}: a struct field, e.g. {@code "Actor.pathX"} → {@code structs.Actor.pathX}
 *   <li>{@link #global(String)}: a global's RVA, e.g. {@code "client"} → {@code globals.client}
 *   <li>{@link #function(String)}: a function's RVA, e.g. {@code "worldToScreen"} → {@code functions.worldToScreen}
 *   <li>{@link #container(String)}: a container layout value, e.g. {@code "hash_map.node.value"}
 * </ul>
 */
public final class Mapping {

    private final JsonObject root;
    private final String version;
    private final String sha256;

    private Mapping(JsonObject root) {
        this.root = root;
        this.version = string(root, "version");
        this.sha256 = string(root, "sha256");
    }

    /** Classpath folder holding one {@code <version>.json} per supported client version. */
    public static final String RESOURCE_DIR = "mappings/";

    public static Mapping load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return parse(reader);
        }
    }

    /** The mapping bundled with the API for {@code version} (e.g. {@code "241.3"}), or null if there is none. */
    public static Mapping bundled(String version) throws IOException {
        InputStream in = Mapping.class.getClassLoader().getResourceAsStream(RESOURCE_DIR + version + ".json");
        if (in == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    private static Mapping parse(Reader reader) {
        return new Mapping(new Gson().fromJson(reader, JsonObject.class));
    }

    /** The client version this mapping is for, e.g. {@code "241.3"}. */
    public String version() {
        return version;
    }

    /** SHA-256 of the exact {@code osclient.exe} the mapping was made from. */
    public String sha256() {
        return sha256;
    }

    /** Byte offset of a struct field, e.g. {@code "Actor.pathX"} or {@code "Client.camera.pos"}. */
    public int offset(String key) {
        String[] parts = split(key);
        return (int) parse(obj(obj(root, "structs"), parts[0]), parts[1], key);
    }

    /** Byte offset of a field, or {@code fallback} if the mapping does not define it. */
    public int offsetOr(String key, int fallback) {
        try {
            return offset(key);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Whether the mapping has a struct field, e.g. {@code "Client.worldFlags"}. Older mappings may lack newer keys. */
    public boolean has(String key) {
        try {
            offset(key);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** RVA (relative to the module base) of a global, e.g. {@code "client"}. */
    public long global(String key) {
        return parse(obj(root, "globals"), key, key);
    }

    /** Whether the mapping has a global, e.g. {@code "varpCount"}. */
    public boolean hasGlobal(String key) {
        try {
            global(key);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * RVA (relative to the module base) of a mapped function, e.g. {@code "worldToScreen"} or
     * {@code "dispatchEvent"}.
     *
     * @param key the function name under {@code functions}
     * @return the function's RVA
     */
    public long function(String key) {
        return parse(obj(root, "functions"), key, key);
    }

    /**
     * Whether the mapping has a function, e.g. {@code "dispatchEvent"}. Older mappings may lack newer keys.
     *
     * @param key the function name under {@code functions}
     * @return true if the mapping defines it
     */
    public boolean hasFunction(String key) {
        try {
            function(key);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * One value of an enum table, e.g. {@code enumValue("events", "ON_GAME_TICK")} → 44, or {@code fallback} if the
     * mapping doesn't have it.
     */
    public int enumValue(String table, String name, int fallback) {
        Map<String, Integer> values = enumTable(table);
        return values.getOrDefault(name, fallback);
    }

    /** A whole enum table ({@code enums.<table>}) as name → value, or an empty map if the mapping doesn't have it. */
    public Map<String, Integer> enumTable(String table) {
        JsonObject enums = root.getAsJsonObject("enums");
        JsonObject values = enums == null ? null : enums.getAsJsonObject(table);
        if (values == null) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                out.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        return out;
    }

    /** A container layout value, e.g. {@code "string.heapFlagByte"} or {@code "hash_map.node.value"}. */
    public int container(String key) {
        String[] parts = split(key);
        return (int) parse(obj(obj(root, "containers"), parts[0]), parts[1], key);
    }

    private static String[] split(String key) {
        int dot = key.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("Expected a dotted key, got: " + key);
        }
        return new String[]{key.substring(0, dot), key.substring(dot + 1)};
    }

    private static JsonObject obj(JsonObject parent, String name) {
        JsonObject child = parent.getAsJsonObject(name);
        if (child == null) {
            throw new MissingMappingException(name);
        }
        return child;
    }

    private static String string(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new MissingMappingException(name);
        }
        return value.getAsString();
    }

    private static long parse(JsonObject parent, String name, String key) {
        String hexOrDec = string(parent, name);
        try {
            return Long.decode(hexOrDec);
        } catch (NumberFormatException e) {
            throw new MissingMappingException(key + " (unparsable value '" + hexOrDec + "')");
        }
    }

    /** Thrown when a requested key is absent from the mapping. */
    public static final class MissingMappingException extends RuntimeException {
        public MissingMappingException(String key) {
            super("Mapping has no entry for: " + key);
        }
    }
}
