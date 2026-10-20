package com.kraken.api.widgets;

/**
 * Packing and unpacking component ids, as RuneLite's {@code WidgetUtil}. A component id is
 * {@code interface << 16 | child}, the same packing the native client keys its components with.
 */
public final class WidgetUtil {

    private WidgetUtil() {
    }

    /**
     * The interface (group) a component belongs to.
     *
     * @param componentId the packed component id
     * @return the interface id
     */
    public static int componentToInterface(int componentId) {
        return componentId >>> 16;
    }

    /**
     * A component's id within its interface.
     *
     * @param componentId the packed component id
     * @return the child id
     */
    public static int componentToId(int componentId) {
        return componentId & 0xFFFF;
    }

    /**
     * Packs a component id.
     *
     * @param interfaceId the interface (group) id, e.g. from {@code gameval.InterfaceID}
     * @param childId     the child's id within the interface
     * @return {@code interfaceId << 16 | childId}
     */
    public static int packComponentId(int interfaceId, int childId) {
        return interfaceId << 16 | childId;
    }
}
