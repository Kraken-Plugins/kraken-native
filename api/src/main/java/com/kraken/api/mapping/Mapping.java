package com.kraken.api.mapping;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A single build's offset table, loaded from {@code mappings/<sha16>.json} (the file the updater emits).
 * The API holds no hard-coded offsets; every struct field, global and container layout comes from here,
 * keyed the same way the updater names them.
 *
 * <ul>
 *   <li>{@link #offset(String)} — a struct field, e.g. {@code "Actor.pathX"} → {@code structs.Actor.fields.pathX.offset}
 *   <li>{@link #global(String)} — a global's RVA, e.g. {@code "client"} → {@code globals.client.rva}
 *   <li>{@link #container(String)} — a container field, e.g. {@code "hash_map.node.value"}
 * </ul>
 */
public final class Mapping {

    private final JsonObject root;
    private final String sha16;

    private Mapping(JsonObject root, String sha16) {
        this.root = root;
        this.sha16 = sha16;
    }

    public static Mapping load(Path file, String sha16) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            return new Mapping(root, sha16);
        }
    }

    /** The 16-hex-digit build id this mapping is for. */
    public String build() {
        return sha16;
    }

    /** Byte offset of a struct field, e.g. {@code "Actor.pathX"}. */
    public int offset(String key) {
        String[] parts = split(key);
        JsonObject fields = obj(obj(obj(root, "structs"), parts[0]), "fields");
        return (int) parse(obj(fields, parts[1]).get("offset").getAsString(), key);
    }

    /** Byte offset of a field, or {@code fallback} if the mapping does not define it. */
    public int offsetOr(String key, int fallback) {
        try {
            return offset(key);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** RVA (relative to the module base) of a global, e.g. {@code "client"}. */
    public long global(String key) {
        return parse(obj(obj(root, "globals"), key).get("rva").getAsString(), key);
    }

    /** A container layout value, e.g. {@code "string.heapFlagByte"} or {@code "hash_map.node.value"}. */
    public int container(String key) {
        String[] parts = split(key);
        JsonObject group = obj(obj(root, "containers"), parts[0]);
        return (int) parse(obj(group, parts[1]).get("value").getAsString(), key);
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

    private static long parse(String hexOrDec, String key) {
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
