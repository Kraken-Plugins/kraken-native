package com.kraken.client;

import com.kraken.api.action.ClickPolicy;
import com.kraken.client.plugins.config.Alpha;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.ShowIf;
import com.kraken.client.plugins.config.Units;
import com.kraken.client.ui.overlay.OverlayFont;
import com.kraken.client.ui.overlay.components.ComponentConstants;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Kraken's own settings: the Settings sidebar page is built from it. Group {@value #GROUP}, which also holds the
 * plugins' on/off switches, and, in hidden items, the window's layout from the last session.
 */
@ConfigGroup(KrakenConfig.GROUP)
public interface KrakenConfig extends Config {

    /**
     * The group Kraken's settings are kept in.
     */
    String GROUP = "kraken";

    /**
     * The section for the window.
     */
    @ConfigSection(name = "Window", description = "The window's size, place and behaviour", position = 0)
    String window = "window";

    /**
     * The section for frame rates.
     */
    @ConfigSection(name = "Frame rate", description = "The game's frame rate and the overlays'", position = 20)
    String frameRate = "frameRate";

    /**
     * The section for plugin overlays.
     */
    @ConfigSection(name = "Overlays", description = "Plugin overlays drawn over the game", position = 30)
    String overlays = "overlays";

    /**
     * The section for the actions plugins send to the game.
     */
    @ConfigSection(name = "Actions", description = "How plugins' actions reach the game", position = 40)
    String actions = "actions";

    /**
     * The game's size in screen pixels, which the window is fitted to.
     *
     * @return the width and height
     */
    @Range(min = 240, max = 7680)
    @ConfigItem(keyName = "gameSize", name = "Game size",
            description = "The game's size in screen pixels. Kraken opens at this size, unless it reopens maximised, "
                    + "and resizes to it when this changes.",
            position = 1, section = window)
    default Dimension gameSize() {
        return new Dimension(765, 503);
    }

    /**
     * Whether Kraken reopens where it was last left.
     *
     * @return true to restore the last position and maximised state
     */
    @ConfigItem(keyName = "rememberWindow", name = "Remember position",
            description = "Reopen where Kraken was last left, maximised if it was. When off, it opens centred. "
                    + "Either way the game opens at the game size.",
            position = 2, section = window)
    default boolean rememberWindow() {
        return true;
    }

    /**
     * Whether the window's edges can be dragged to resize it.
     *
     * @return true to stop the user resizing the window
     */
    @ConfigItem(keyName = "lockWindowSize", name = "Lock window size",
            description = "Stop the window being resized by dragging its edges.", position = 3, section = window)
    default boolean lockWindowSize() {
        return false;
    }

    /**
     * Whether opening a sidebar panel widens the window rather than narrowing the game.
     *
     * @return true to grow the window
     */
    @ConfigItem(keyName = "growWindow", name = "Grow window for panels",
            description = "Widen the window when a panel opens instead of shrinking the game.", position = 4,
            section = window)
    default boolean growWindow() {
        return true;
    }

    /**
     * Whether the window stays above other windows.
     *
     * @return true to keep Kraken on top
     */
    @ConfigItem(keyName = "alwaysOnTop", name = "Always on top", description = "Keep Kraken above other windows.",
            position = 5, section = window)
    default boolean alwaysOnTop() {
        return false;
    }

    /**
     * Whether the window's title names the logged-in player.
     *
     * @return true to add the player's name to the title
     */
    @ConfigItem(keyName = "playerNameInTitle", name = "Player name in title",
            description = "Add the logged-in player's name to the window title, which the taskbar and Alt+Tab show, "
                    + "to tell several clients apart.",
            position = 6, section = window)
    default boolean playerNameInTitle() {
        return true;
    }

    /**
     * When closing the window asks first. Closing Kraken closes the game too.
     *
     * @return when to ask
     */
    @ConfigItem(keyName = "confirmExit", name = "Confirm before closing",
            description = "Ask before closing Kraken, which closes the game with it.", position = 7, section = window)
    default ExitConfirmation confirmExit() {
        return ExitConfirmation.LOGGED_IN;
    }

    /**
     * Whether the game's and the overlays' frame rates are drawn over the game.
     *
     * @return true to draw them
     */
    @ConfigItem(keyName = "showFps", name = "Show FPS",
            description = "Draw the game's frame rate and the overlays' at the top of the game. The game's needs the "
                    + "native hook.",
            position = 21, section = frameRate)
    default boolean showFps() {
        return false;
    }

    /**
     * Whether the game is held to a lower frame rate while Kraken isn't the focused window.
     *
     * @return true to cap the game while unfocused
     */
    @ConfigItem(keyName = "limitUnfocusedFps", name = "Limit FPS when unfocused",
            description = "Hold the game to a lower frame rate while another app has focus, to save power. Needs the "
                    + "native hook.",
            position = 22, section = frameRate)
    default boolean limitUnfocusedFps() {
        return false;
    }

    /**
     * The frame rate the game is held to while Kraken is unfocused, when {@link #limitUnfocusedFps} is on.
     *
     * @return the frame rate
     */
    @Range(min = 1, max = 50)
    @Units(Units.FPS)
    @ConfigItem(keyName = "unfocusedFps", name = "Unfocused frame rate",
            description = "The frame rate the game is held to while Kraken is unfocused.", position = 23,
            section = frameRate)
    default int unfocusedFps() {
        return 20;
    }

    /**
     * Whether the game waits at the end of each frame for that frame's overlay, so overlays are drawn from the same
     * moment of the game as the frame they're shown with. Needs the native hook, and a mapping that names the GL
     * device's frame queue.
     *
     * @return true to keep overlays in step with the game
     */
    @ConfigItem(keyName = "syncOverlays", name = "Keep in step with the game",
            description = "Draw each overlay frame from the same moment as the game frame it's shown with, so "
                    + "outlines and labels stay on what they mark while the camera or the player moves. The game "
                    + "waits for each overlay frame (up to 10 ms), so overlays that are slow to draw cost game frame "
                    + "rate. Needs the native hook.",
            position = 31, section = overlays)
    default boolean syncOverlays() {
        return true;
    }

    /**
     * The most overlay frames to draw a second, while they aren't kept in step with the game: inside the game,
     * overlays are redrawn with each frame the game draws, up to this; in the window over the game, at this rate, up
     * to 50.
     *
     * @return the frame rate
     */
    @Range(min = 1, max = 240)
    @Units(Units.FPS)
    @ConfigItem(keyName = "overlayFrameRate", name = "Frame rate",
            description = "The most times a second plugin overlays are redrawn while they aren't kept in step with "
                    + "the game. Inside the game they're redrawn with each frame the game draws, up to this; below "
                    + "the game's frame rate, they lag behind the scene. Every frame reads the game, so a higher rate "
                    + "costs more. Without the native hook, at most 50.",
            position = 32, section = overlays)
    default int overlayFrameRate() {
        return 240;
    }

    /**
     * The font overlay panels are drawn in.
     *
     * @return the font
     */
    @ConfigItem(keyName = "overlayFont", name = "Font",
            description = "The font overlay panels are drawn in. Labels drawn over the scene use its bold weight.",
            position = 33, section = overlays)
    default OverlayFont overlayFont() {
        return OverlayFont.REGULAR;
    }

    /**
     * The size of overlay text, in game pixels.
     *
     * @return the size
     */
    @Range(min = 8, max = 32)
    @Units(Units.PIXELS)
    @ConfigItem(keyName = "overlayFontSize", name = "Font size", description = "The size of overlay text.",
            position = 34, section = overlays)
    default int overlayFontSize() {
        return 12;
    }

    /**
     * The background overlay panels are drawn on, unless an overlay asks for its own.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "overlayBackground", name = "Panel background",
            description = "The colour behind overlay panels.", position = 35, section = overlays)
    default Color overlayBackground() {
        return ComponentConstants.STANDARD_BACKGROUND_COLOR;
    }

    /**
     * Whether plugins that can draw their world overlays (tiles and names that follow NPCs) inside the game's own
     * frames do so: the native hook places them anew in every frame, from that frame's camera and positions, and the
     * game never waits for them. NPC Indicators can. Needs the native hook.
     *
     * @return true to draw world overlays in the game's frames
     */
    @ConfigItem(keyName = "worldOverlaysInFrame", name = "Draw world overlays in the game's frame",
            description = "Plugins that support it (NPC Indicators) draw their tiles and names inside the game's own "
                    + "frames. They're placed in every frame from that frame's camera and positions, so they stay on "
                    + "what they mark while the camera, the player or an NPC moves, and the game never waits for "
                    + "them. Experimental. Needs the native hook.",
            position = 36, section = overlays)
    default boolean worldOverlaysInFrame() {
        return false;
    }

    /**
     * Whether plugins drawing their world overlays in the game's frames also draw them the old way, in magenta, to
     * compare the two.
     *
     * @return true to draw both
     */
    @ShowIf(key = "worldOverlaysInFrame", values = {"true"})
    @ConfigItem(keyName = "compareWorldOverlays", name = "Compare with the overlay",
            description = "Also draw the same tiles and names the old way, on the overlay, in magenta, to compare "
                    + "the two side by side.",
            position = 37, section = overlays)
    default boolean compareWorldOverlays() {
        return false;
    }

    /**
     * Whether a mouse click goes before each action a plugin sends, and where it lands.
     *
     * @return the click policy: {@link ClickPolicy#ON_SCREEN} by default
     */
    @ConfigItem(keyName = "clickPolicy", name = "Mouse click",
            description = "Whether a mouse click goes before each action plugins send. On screen: at the subject, "
                    + "when it is on the canvas, else at the click point below. Static point: always at the click "
                    + "point. None: no click, the action alone.",
            position = 41, section = actions)
    default ClickPolicy clickPolicy() {
        return ClickPolicy.ON_SCREEN;
    }

    /**
     * The canvas point the mouse click lands on when the policy is static, or when it is on screen and the subject
     * isn't on the canvas.
     *
     * @return the point, in canvas pixels; (0, 0) by default
     */
    @ShowIf(key = "clickPolicy", values = {"ON_SCREEN", "STATIC"})
    @ConfigItem(keyName = "staticClickPoint", name = "Click point",
            description = "The canvas point the mouse click lands on when it doesn't land on the subject: always, "
                    + "for a static click; for an on screen click, when the subject is off the canvas. Canvas "
                    + "pixels, x then y.",
            position = 42, section = actions)
    default Point staticClickPoint() {
        return new Point(0, 0);
    }

    /**
     * The window's bounds when it was last in its normal (not maximised) state.
     *
     * @return the bounds, or null if none were saved
     */
    @ConfigItem(keyName = "windowBounds", name = "Window bounds", description = "The window's last normal bounds.",
            hidden = true)
    default Rectangle windowBounds() {
        return null;
    }

    /**
     * Saves the window's bounds.
     *
     * @param bounds the bounds
     */
    @ConfigItem(keyName = "windowBounds", name = "Window bounds", description = "The window's last normal bounds.",
            hidden = true)
    void windowBounds(Rectangle bounds);

    /**
     * Whether the window was maximised.
     *
     * @return true if it was
     */
    @ConfigItem(keyName = "windowMaximized", name = "Window maximised",
            description = "Whether the window was maximised.", hidden = true)
    default boolean windowMaximized() {
        return false;
    }

    /**
     * Saves whether the window is maximised.
     *
     * @param maximized true if it is
     */
    @ConfigItem(keyName = "windowMaximized", name = "Window maximised",
            description = "Whether the window was maximised.", hidden = true)
    void windowMaximized(boolean maximized);

    /**
     * The sidebar panels' width.
     *
     * @return the width in pixels, 0 for the narrowest
     */
    @ConfigItem(keyName = "sidebarWidth", name = "Sidebar width", description = "The sidebar panels' width.",
            hidden = true)
    default int sidebarWidth() {
        return 0;
    }

    /**
     * Saves the sidebar panels' width.
     *
     * @param width the width in pixels
     */
    @ConfigItem(keyName = "sidebarWidth", name = "Sidebar width", description = "The sidebar panels' width.",
            hidden = true)
    void sidebarWidth(int width);

    /**
     * The sidebar panel that was open.
     *
     * @return its title, or "" if none was
     */
    @ConfigItem(keyName = "sidebarPanel", name = "Sidebar panel", description = "The sidebar panel that was open.",
            hidden = true)
    default String sidebarPanel() {
        return "";
    }

    /**
     * Saves which sidebar panel is open.
     *
     * @param title its title, or "" if none is
     */
    @ConfigItem(keyName = "sidebarPanel", name = "Sidebar panel", description = "The sidebar panel that was open.",
            hidden = true)
    void sidebarPanel(String title);

    /**
     * Whether the console was expanded.
     *
     * @return true if it was
     */
    @ConfigItem(keyName = "consoleExpanded", name = "Console expanded",
            description = "Whether the console was expanded.", hidden = true)
    default boolean consoleExpanded() {
        return true;
    }

    /**
     * Saves whether the console is expanded.
     *
     * @param expanded true if it is
     */
    @ConfigItem(keyName = "consoleExpanded", name = "Console expanded",
            description = "Whether the console was expanded.", hidden = true)
    void consoleExpanded(boolean expanded);

    /**
     * The expanded console's height.
     *
     * @return the height in pixels
     */
    @ConfigItem(keyName = "consoleHeight", name = "Console height", description = "The expanded console's height.",
            hidden = true)
    default int consoleHeight() {
        return 160;
    }

    /**
     * Saves the expanded console's height.
     *
     * @param height the height in pixels
     */
    @ConfigItem(keyName = "consoleHeight", name = "Console height", description = "The expanded console's height.",
            hidden = true)
    void consoleHeight(int height);
}
