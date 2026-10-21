package com.kraken.client.ui.devtools;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.GameSession;
import com.kraken.client.GameView;
import com.kraken.client.ProcessAttacher;
import com.kraken.client.ui.devtools.DevTool.Support;
import com.kraken.client.ui.devtools.tools.DevToolCatalog;
import com.kraken.client.ui.devtools.view.ToolWindow;
import com.kraken.client.ui.overlay.GameOverlay;
import com.kraken.client.ui.overlay.OverlayContext;
import com.kraken.client.ui.overlay.OverlayPainter;
import lombok.Getter;
import lombok.extern.java.Log;

import java.awt.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.prefs.Preferences;
import javax.swing.*;

/**
 * Runs the dev tools: which overlays are on and which windows are open (remembered between sessions), reading each
 * showing tool once per API poll, and drawing them over the game or into their windows. State changes happen on the
 * EDT; reads happen on the API poll thread.
 */
@Log(topic = "kraken")
@Singleton
public final class DevToolManager {
    private final ProcessAttacher attacher;
    private final GameOverlay overlay;
    private final GameView view;
    @Getter
    private final List<DevTool<?>> tools = DevToolCatalog.create();
    @Getter
    private final Preferences prefs = Preferences.userNodeForPackage(DevToolManager.class);
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<DevTool<?>, ToolWindow> windows = new HashMap<>();
    private final Set<DevTool<?>> failing = ConcurrentHashMap.newKeySet();

    @Inject
    DevToolManager(ProcessAttacher attacher, GameOverlay overlay, GameView view) {
        this.attacher = attacher;
        this.overlay = overlay;
        this.view = view;
        for (DevTool<?> tool : tools) {
            tool.setHost(host(tool));
            tool.setOverlayOn(tool.overlaySupport() == Support.READY && prefs.getBoolean(overlayKey(tool), false));
            for (ToolOption option : tool.options()) {
                option.setOn(prefs.getBoolean(optionKey(tool, option), option.isDefaultValue()));
            }
        }
        overlay.addPainter(new OverlayPainter() {
            @Override
            public boolean isActive() {
                return attacher.isAttached() && tools.stream().anyMatch(t -> t.isOverlayOn() && t.overlaySupport() == Support.READY);
            }

            @Override
            public void paint(OverlayContext context) {
                paintOverlays(context);
            }
        });
        attacher.addPollListener(this::poll);
        attacher.addStateListener(this::attachStateChanged);
    }

    /** Runs {@code listener} on the EDT after any tool's overlay, window or option changes. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void setOverlay(DevTool<?> tool, boolean on) {
        if (on && tool.overlaySupport() != Support.READY || on == tool.isOverlayOn()) {
            return;
        }
        tool.setOverlayOn(on);
        prefs.putBoolean(overlayKey(tool), on);
        changed(tool);
    }

    /** Opens the tool's window (or brings it to the front), or closes it. */
    public void setWindowOpen(DevTool<?> tool, boolean open) {
        ToolWindow window = windows.get(tool);
        if (open && window != null) {
            window.toFront();
            return;
        }
        if (open == (window != null) || open && tool.windowSupport() != Support.READY) {
            return;
        }
        if (open) {
            window = new ToolWindow(SwingUtilities.getWindowAncestor(view), tool.getName(), tool.openView(), prefs,
                    "window." + tool.getId(), windows.size(), () -> setWindowOpen(tool, false));
            windows.put(tool, window);
            window.setVisible(true);
        } else {
            windows.remove(tool);
            tool.closeView();
            window.close();
        }
        changed(tool);
    }

    public void setOption(DevTool<?> tool, ToolOption option, boolean on) {
        option.setOn(on);
        prefs.putBoolean(optionKey(tool, option), on);
        overlay.refresh();
        listeners.forEach(Runnable::run);
    }

    public boolean anyActive() {
        return tools.stream().anyMatch(DevTool::isActive);
    }

    /** Turns every overlay off and closes every tool window. */
    public void turnAllOff() {
        for (DevTool<?> tool : tools) {
            setOverlay(tool, false);
            setWindowOpen(tool, false);
        }
    }

    private void changed(DevTool<?> tool) {
        updateLifecycle(tool);
        overlay.refresh();
        listeners.forEach(Runnable::run);
    }

    private void attachStateChanged() {
        tools.forEach(this::updateLifecycle);
        overlay.refresh();
    }

    /** Starts a tool when it begins showing with the API attached, and stops it otherwise. */
    private void updateLifecycle(DevTool<?> tool) {
        GameSession session = attacher.attached();
        if (tool.isActive() && session != null) {
            tool.start(session);
        } else {
            tool.stop();
        }
    }

    /** Poll thread: refreshes every showing tool, then hands the results to the EDT in one go. */
    private void poll(GameSession session) {
        List<Runnable> updates = new ArrayList<>();
        boolean overlays = false;
        for (DevTool<?> tool : tools) {
            if (!tool.isActive()) {
                continue;
            }
            try {
                Runnable update = tool.refresh(session);
                if (update != null) {
                    updates.add(update);
                }
                failing.remove(tool);
            } catch (RuntimeException e) {
                if (failing.add(tool)) {
                    log.log(Level.WARNING, "Dev tool '" + tool.getName() + "' failed to read the game", e);
                }
            }
            overlays |= tool.isOverlayOn();
        }
        if (!updates.isEmpty()) {
            SwingUtilities.invokeLater(() -> updates.forEach(Runnable::run));
        }
        if (overlays) {
            overlay.refresh();
        }
    }

    private void paintOverlays(OverlayContext context) {
        for (DevTool<?> tool : tools) {
            if (tool.isOverlayOn() && tool.overlaySupport() == Support.READY) {
                try {
                    tool.paint(context);
                } catch (RuntimeException e) {
                    if (failing.add(tool)) {
                        log.log(Level.WARNING, "Dev tool '" + tool.getName() + "' failed to draw its overlay", e);
                    }
                }
            }
        }
    }

    /**
     * What a tool reaches of the window through {@link DevTool#host()}.
     *
     * @param tool the tool
     * @return its host
     */
    private DevTool.Host host(DevTool<?> tool) {
        return new DevTool.Host() {
            @Override
            public Component gameArea() {
                return view;
            }

            @Override
            public void setOverlay(boolean on) {
                DevToolManager.this.setOverlay(tool, on);
            }
        };
    }

    private static String overlayKey(DevTool<?> tool) {
        return "overlay." + tool.getId();
    }

    private static String optionKey(DevTool<?> tool, ToolOption option) {
        return "option." + tool.getId() + "." + option.getKey();
    }
}
