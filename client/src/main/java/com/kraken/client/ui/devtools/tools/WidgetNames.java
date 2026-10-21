package com.kraken.client.ui.devtools.tools;

import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.SpriteID;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * The cache's names for interface groups, their components and sprites, taken from the generated {@code gameval}
 * constants: group 149 is {@code INVENTORY}, component {@code 0x0095_0000} is {@code Inventory.ITEMS}. Built once, on
 * first use, by reading the constants' fields; immutable after, so any thread may use it.
 */
final class WidgetNames {

    /**
     * A component's name.
     *
     * @param owner the constant class it is declared in, e.g. {@code Inventory}
     * @param field the constant's name, e.g. {@code ITEMS}
     */
    record Name(String owner, String field) {

        /**
         * How code refers to the component.
         *
         * @return e.g. {@code InterfaceID.Inventory.ITEMS}
         */
        String constant() {
            return "InterfaceID." + owner + "." + field;
        }
    }

    private static volatile WidgetNames instance;

    private final Map<Integer, String> groups;
    private final Map<Integer, Name> components;
    private final Map<Integer, String> sprites;

    /**
     * Names from explicit maps.
     *
     * @param groups     group names by group id
     * @param components component names by packed id
     * @param sprites    sprite names by sprite id
     */
    WidgetNames(Map<Integer, String> groups, Map<Integer, Name> components, Map<Integer, String> sprites) {
        this.groups = Map.copyOf(groups);
        this.components = Map.copyOf(components);
        this.sprites = Map.copyOf(sprites);
    }

    /**
     * The names from {@code gameval}, read on the first call.
     *
     * @return the names
     */
    static WidgetNames get() {
        WidgetNames names = instance;
        if (names == null) {
            synchronized (WidgetNames.class) {
                names = instance;
                if (names == null) {
                    names = load();
                    instance = names;
                }
            }
        }
        return names;
    }

    /**
     * A group's name.
     *
     * @param groupId the group id
     * @return e.g. {@code INVENTORY}; "" if it has none
     */
    String group(int groupId) {
        return groups.getOrDefault(groupId, "");
    }

    /**
     * A static component's name.
     *
     * @param packedId the component's {@code group << 16 | component}
     * @return the name, or null if it has none
     */
    Name component(int packedId) {
        return components.get(packedId);
    }

    /**
     * A sprite's name.
     *
     * @param spriteId the sprite id
     * @return e.g. {@code COMPASS}; "" if it has none
     */
    String sprite(int spriteId) {
        return sprites.getOrDefault(spriteId, "");
    }

    /**
     * Reads the constants: {@code InterfaceID}'s own fields name groups, the fields of its nested classes name
     * components, and {@code SpriteID}'s own fields name sprites.
     *
     * @return the names
     */
    private static WidgetNames load() {
        Map<Integer, String> groups = new HashMap<>();
        constants(InterfaceID.class, (name, value) -> groups.putIfAbsent(value, name));
        Map<Integer, Name> components = new HashMap<>();
        for (Class<?> owner : InterfaceID.class.getClasses()) {
            constants(owner, (name, value) -> components.putIfAbsent(value, new Name(owner.getSimpleName(), name)));
        }
        Map<Integer, String> sprites = new HashMap<>();
        constants(SpriteID.class, (name, value) -> sprites.putIfAbsent(value, name));
        return new WidgetNames(groups, components, sprites);
    }

    /**
     * Calls back with each {@code public static final int} a class declares.
     *
     * @param type    the class
     * @param visitor gets each constant's name and value
     */
    private static void constants(Class<?> type, ConstantVisitor visitor) {
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != int.class || !Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers)) {
                continue;
            }
            try {
                visitor.visit(field.getName(), field.getInt(null));
            } catch (IllegalAccessException e) {
                // a public constant is always readable
            }
        }
    }

    @FunctionalInterface
    private interface ConstantVisitor {
        void visit(String name, int value);
    }
}
