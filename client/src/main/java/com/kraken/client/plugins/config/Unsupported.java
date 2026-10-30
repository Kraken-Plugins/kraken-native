package com.kraken.client.plugins.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link ConfigItem} that the client can't act on yet. The settings page shows it greyed out, its tooltip
 * saying it is currently unsupported and why. The item keeps its key, default and stored value, so a plugin ported
 * with every setting keeps them all, and the setting starts working once the client can back it and the annotation
 * is removed.
 *
 * <pre>{@code
 * @Unsupported("Needs right-click menu edits")
 * @ConfigItem(keyName = "highlightMenuStyle", name = "Highlight menu style",
 *         description = "Highlight style in the right-click menu.")
 * default MenuHighlightStyle highlightMenuStyle() { return MenuHighlightStyle.NONE; }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Unsupported {

    /**
     * What the setting needs that the client can't do yet, shown in its tooltip.
     *
     * @return the reason, e.g. "Needs right-click menu edits", or "" for none
     */
    String value() default "";
}
