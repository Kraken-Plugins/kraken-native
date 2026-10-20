package com.kraken.client.ui.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import org.kordamp.ikonli.Ikon;

/**
 * A tool the API can't support yet. It holds its place in the sidebar, disabled, saying what it will show and what
 * it needs. Replace it with a real {@link DevTool} once the API can read its data.
 */
final class PlannedTool extends DevTool<Void> {
    private final Support overlay;
    private final Support window;
    private final String needs;

    private PlannedTool(String id, String name, Category category, Ikon icon, String description,
                        boolean overlay, boolean window, String needs) {
        super(id, name, category, icon, description);
        this.overlay = overlay ? Support.PLANNED : Support.NONE;
        this.window = window ? Support.PLANNED : Support.NONE;
        this.needs = needs;
    }

    /** Will draw over the game. */
    static PlannedTool overlay(String id, String name, Category category, Ikon icon, String description, String needs) {
        return new PlannedTool(id, name, category, icon, description, true, false, needs);
    }

    /** Will open in a window. */
    static PlannedTool window(String id, String name, Category category, Ikon icon, String description, String needs) {
        return new PlannedTool(id, name, category, icon, description, false, true, needs);
    }

    /** Will draw over the game and open in a window. */
    static PlannedTool both(String id, String name, Category category, Ikon icon, String description, String needs) {
        return new PlannedTool(id, name, category, icon, description, true, true, needs);
    }

    @Override
    public Support overlaySupport() {
        return overlay;
    }

    @Override
    public Support windowSupport() {
        return window;
    }

    @Override
    public String needs() {
        return needs;
    }

    @Override
    protected Void read(KrakenApi api) {
        return null;
    }
}
