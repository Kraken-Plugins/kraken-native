package com.kraken.client.devtools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.MapCoord;
import com.kraken.client.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import lombok.Getter;
import org.kordamp.ikonli.Ikon;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One tool in the dev tools sidebar. A tool reads the game into an immutable snapshot on the API poll thread
 * ({@link #read}), then shows it over the game ({@link #paintOverlay}), in a popout window ({@link #createView}), or
 * both. It only reads while one of those is showing, so tools that are off cost nothing.
 *
 * <p>To add a tool: subclass this, return {@link Support#READY} for the surfaces it has, and list it in
 * {@code DevToolCatalog}.
 *
 * @param <S> the snapshot type. It is handed from the poll thread to the EDT, so it must be immutable.
 */
public abstract class DevTool<S> {

    /** Whether a tool has a given surface (overlay or window). */
    public enum Support {
        /** Not part of this tool. */
        NONE,
        /** Works now. */
        READY,
        /** Coming once the API can read what it needs (see {@link #needs()}); shown disabled. */
        PLANNED
    }

    @Getter
    private final String id;
    @Getter
    private final String name;
    @Getter
    private final Category category;
    @Getter
    private final Ikon icon;
    @Getter
    private final String description;
    private final List<ToolOption> options = new ArrayList<>();

    // Runtime state, driven by DevToolManager: written on the EDT, read on the poll thread
    private volatile boolean overlayOn;
    private volatile ToolView<S> view;
    private volatile S latest;
    private boolean running;

    protected DevTool(String id, String name, Category category, Ikon icon, String description) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.icon = icon;
        this.description = description;
    }

    public Support overlaySupport() {
        return Support.NONE;
    }

    public Support windowSupport() {
        return Support.NONE;
    }

    /** What the API still needs before the planned surfaces work, or null. Shown in the sidebar. */
    public String needs() {
        return null;
    }

    public final List<ToolOption> options() {
        return Collections.unmodifiableList(options);
    }

    /** True if neither surface works yet. */
    public final boolean isPlanned() {
        return overlaySupport() != Support.READY && windowSupport() != Support.READY;
    }

    public final boolean isOverlayOn() {
        return overlayOn;
    }

    public final boolean isWindowOpen() {
        return view != null;
    }

    /** Showing somewhere, so it's being read every poll. */
    public final boolean isActive() {
        return (overlayOn && overlaySupport() == Support.READY) || view != null;
    }

    /** Declares an option. Call from the constructor; the order is the order shown in the sidebar. */
    protected final ToolOption option(String key, String label, boolean defaultValue) {
        ToolOption option = new ToolOption(key, label, defaultValue);
        options.add(option);
        return option;
    }

    /**
     * Poll thread: reads what this tool shows. {@code api.lua().clientOp()} mirrors Lua's {@code ClientOp},
     * {@code api.lua().clientState()} its {@code ClientState}. Return null when there's nothing to show.
     */
    protected abstract S read(KrakenApi api);

    /** EDT: draws the latest snapshot over the game. */
    protected void paintOverlay(S snapshot, OverlayContext overlay) {
    }

    /** EDT: builds the window content. Called each time the window opens. */
    protected ToolView<S> createView() {
        throw new UnsupportedOperationException(name + " has no window");
    }

    /** EDT: the tool started showing while the API is attached. Subscribe to events here. */
    protected void onStart(KrakenApi api) {
    }

    /** EDT: undo {@link #onStart}. */
    protected void onStop() {
    }

    /** The local player's tile, or null when logged out. */
    protected static MapCoord localTile(ClientOp client) {
        int self = client.getLocalPlayerIndex();
        return self < 0 ? null : client.playerCoord(self);
    }

    final void setOverlayOn(boolean on) {
        overlayOn = on;
    }

    final JComponent openView() {
        ToolView<S> v = createView();
        S snapshot = latest;
        if (snapshot != null) {
            v.update(snapshot);
        }
        view = v;
        return v.component();
    }

    final void closeView() {
        view = null;
    }

    /** Poll thread: reads a fresh snapshot, returning the EDT work that shows it in the open window (or null). */
    final Runnable refresh(KrakenApi api) {
        S snapshot = read(api);
        latest = snapshot;
        ToolView<S> v = view;
        if (snapshot == null || v == null) {
            return null;
        }
        return () -> {
            if (view == v) {
                v.update(snapshot);
            }
        };
    }

    final void paint(OverlayContext overlay) {
        S snapshot = latest;
        if (snapshot != null) {
            paintOverlay(snapshot, overlay);
        }
    }

    final void start(KrakenApi api) {
        if (!running) {
            running = true;
            onStart(api);
        }
    }

    final void stop() {
        if (running) {
            running = false;
            onStop();
        }
        latest = null;
    }
}
