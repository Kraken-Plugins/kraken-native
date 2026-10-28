package com.kraken.client.plugins.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shows a {@link ConfigItem} or a {@link ConfigSection} on the settings page only while another item of the same
 * config has one of the given values. The page updates as the values change.
 *
 * <pre>{@code
 * @ConfigItem(keyName = "shape", name = "Shape", description = "The marker's shape")
 * default Shape shape() { return Shape.TILE; }
 *
 * @ShowIf(key = "shape", values = {"CIRCLE", "SQUARE"})
 * @ConfigItem(keyName = "radius", name = "Radius", description = "How far the marker reaches")
 * default int radius() { return 2; }
 *
 * @ShowIf(key = "outline")
 * @ConfigItem(keyName = "outlineWidth", name = "Outline width", description = "The outline's width")
 * default int outlineWidth() { return 1; }
 * }</pre>
 *
 * <ul>
 *   <li>A value matches the item's value as it is stored: an enum's constant name, {@code true} or {@code false}, a
 *       number as written, text exactly. For a {@code Set} of an enum, any of the values being in the set matches.
 *   <li>Several on one item or section must all hold.
 *   <li>An item or section whose condition names an item that isn't shown isn't shown either, so a chain of them
 *       nests: a setting under a switch under a drop-down disappears with the drop-down's choice. Items in a section
 *       that isn't shown aren't shown. Hidden items ({@link ConfigItem#hidden()}) count as shown here: they hold a
 *       plugin's state, and may decide what the page shows.
 *   <li>A section shows only while at least one of its items does.
 * </ul>
 *
 * {@link ConfigManager#isShown} answers what the page shows, for tests.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
@Repeatable(ShowIf.List.class)
public @interface ShowIf {

    /**
     * The key of the item whose value decides.
     *
     * @return the item's key in the same config
     */
    String key();

    /**
     * The values that show this: any one of them is enough.
     *
     * @return the values as stored; {@code true} by default, for a switch
     */
    String[] values() default {"true"};

    /**
     * Holds several {@link ShowIf}s on one item or section. Java adds it when there are several.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.FIELD})
    @interface List {

        /**
         * The conditions.
         *
         * @return the conditions, all of which must hold
         */
        ShowIf[] value();
    }
}
