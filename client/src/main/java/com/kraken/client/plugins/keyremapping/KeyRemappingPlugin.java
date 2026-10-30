package com.kraken.client.plugins.keyremapping;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.ClientTick;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarClientID;
import com.kraken.api.widgets.Widget;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.input.KeyManager;
import com.kraken.client.input.KeyReport;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lets the camera and function keys be used while the chat is locked: until Enter is pressed, typed characters go
 * nowhere and the configured keys stand in for the arrows and F-keys; Enter or Escape locks the chat again. The native
 * hook rewrites the keys before the game sees them ({@link KeyManager#setRemapTable}) and moves between locked and
 * typing on its own as Enter and Escape go down, so no key waits on Kraken.
 *
 * <p>Differences from the original: the chat input keeps its usual look (the client's own text can't be changed, so
 * "Press Enter to Chat..." isn't shown), the text typed before Escape stays in the input rather than being cleared,
 * and the locked mode isn't lifted for an interface's own text field, which Kraken can't yet tell is focused.
 */
@PluginDescriptor(
        name = "Key Remapping",
        description = "Allows use of WASD keys for camera movement with 'Press Enter to Chat', and remapping number keys to F-keys",
        tags = {"enter", "chat", "wasd", "camera"},
        enabledByDefault = false
)
public class KeyRemappingPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private KeyManager keyManager;

    @Inject
    private KeyRemappingConfig config;

    /**
     * Whether the player unlocked the chat to type, as the hook last reported. Client thread only.
     */
    @Getter(AccessLevel.PACKAGE)
    @Setter(AccessLevel.PACKAGE)
    private boolean typing;

    private final Consumer<KeyReport> reportListener = this::onKeyReport;

    @Provides
    KeyRemappingConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(KeyRemappingConfig.class);
    }

    @Override
    protected void startUp() {
        typing = false;
        keyManager.setRemapTable(KeyRemappingTable.build(config));
        keyManager.addKeyReportListener(reportListener);
        clientThread.invoke(this::updateMode);
    }

    @Override
    protected void shutDown() {
        keyManager.removeKeyReportListener(reportListener);
        keyManager.setRemapTable(List.of());
        keyManager.setRemapMode(KeyRemappingTable.OFF);
        typing = false;
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (KeyRemappingConfig.GROUP.equals(event.getGroup())) {
            keyManager.setRemapTable(KeyRemappingTable.build(config));
        }
    }

    @Subscribe
    public void onClientTick(ClientTick event) {
        updateMode();
    }

    /**
     * Follows the hook's moves between locked and typing, and locks the chat again when Backspace empties the input.
     *
     * @param report what the hook did with a key
     */
    void onKeyReport(KeyReport report) {
        if (report.modeAfter() == KeyRemappingTable.TYPING) {
            typing = true;
        } else if (report.modeBefore() == KeyRemappingTable.TYPING) {
            typing = false;
        }
        if (typing && report.kind() == KeyReport.Kind.PRESSED && report.keyCode() == KeyEvent.VK_BACK_SPACE) {
            String input = client.getVarcStrValue(VarClientID.CHATINPUT);
            if (input == null || input.isEmpty()) {
                typing = false;
            }
        }
        updateMode();
    }

    /**
     * Picks the mode for what the chat is showing now, and tells the hook if it changed.
     */
    void updateMode() {
        int mode = KeyRemappingTable.modeFor(chatboxFocused(), typing, isDialogOpen(), isOptionsDialogOpen());
        if (mode != keyManager.getRemapMode()) {
            keyManager.setRemapMode(mode);
        }
    }

    /**
     * Whether the chatbox takes the keyboard, so keys are worth remapping: it has its key listener, the world map's
     * search isn't open and the report interface isn't open.
     *
     * @return true if keys go to the chat
     */
    boolean chatboxFocused() {
        Widget chatbox = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
        if (chatbox == null || chatbox.getOnKeyListener() == null) {
            return false;
        }
        Widget worldMapSearch = client.getWidget(InterfaceID.Worldmap.MAPLIST_DISPLAY);
        if (worldMapSearch != null && client.getVarcIntValue(VarClientID.WORLDMAP_SEARCHING) == 1) {
            return false;
        }
        return client.getWidget(InterfaceID.Reportabuse.UNIVERSE) == null;
    }

    /**
     * Whether a dialog that takes number keys is open, so the F-keys shouldn't be remapped: the chatbox's message
     * layers are hidden (as the chatbox's own key script checks), or the bank PIN keypad is open.
     *
     * @return true while such a dialog is open
     */
    boolean isDialogOpen() {
        return isHidden(InterfaceID.Chatbox.MES_LAYER_HIDE) || isHidden(InterfaceID.Chatbox.CHATDISPLAY)
                || !isHidden(InterfaceID.BankpinKeypad.UNIVERSE);
    }

    /**
     * Whether an options dialog is open, which takes neither Space nor the Control remap.
     *
     * @return true while one is open
     */
    boolean isOptionsDialogOpen() {
        return client.getWidget(InterfaceID.Chatmenu.OPTIONS) != null;
    }

    private boolean isHidden(int component) {
        Widget w = client.getWidget(component);
        return w == null || w.isSelfHidden();
    }
}
