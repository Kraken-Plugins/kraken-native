package com.kraken.client.plugins.antidrag;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.ScriptID;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.ClientTick;
import com.kraken.api.events.FocusChanged;
import com.kraken.api.events.ScriptPostFired;
import com.kraken.api.events.WidgetLoaded;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.widgets.Widget;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.input.KeyManager;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import lombok.extern.java.Log;

import java.awt.event.KeyEvent;

/**
 * Makes an item wait before it can be dragged, in the inventory, the bank and the other item interfaces, so a quick
 * click never drags it by mistake: it sets the drag dead time of their item slots, through the client's native hook,
 * while the delay is in force (always, or while Shift is held, and not while Control is held if so configured), and
 * puts the game's own delay back otherwise. It also clears the inventory and bank slots' mouse-repeat listener, the
 * game's own {@code inventory_antidrag_update} script.
 *
 * <p>Differences from the original: Shift and Control are read once per client tick, since the game owns the
 * keyboard; and the old inventory drag delay isn't set, as it isn't mapped: the item slots' own dead time does the
 * work.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Anti Drag",
        description = "Prevent dragging an item for a specified delay",
        tags = {"antidrag", "delay", "inventory", "items"},
        enabledByDefault = false
)
public class AntiDragPlugin extends Plugin {

    /**
     * The settings' group.
     */
    static final String CONFIG_GROUP = "antiDrag";

    /**
     * The game's own drag delay for item slots, in client ticks.
     */
    private static final int DEFAULT_DELAY = 5;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private AntiDragConfig config;

    @Inject
    private KeyManager keyManager;

    /**
     * Client thread only.
     */
    private final AntiDragKeys keys = new AntiDragKeys();
    private boolean shiftDown;
    private boolean ctrlDown;

    @Provides
    AntiDragConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(AntiDragConfig.class);
    }

    @Override
    protected void startUp() {
        clientThread.invokeLater(() -> {
            keys.clear();
            shiftDown = false;
            ctrlDown = false;
            if (client.getGameState() == GameState.LOGGED_IN && !config.onShiftOnly()) {
                setDragDelay();
            }
        });
    }

    @Override
    protected void shutDown() {
        clientThread.invoke(this::resetDragDelay);
    }

    /**
     * Reads Shift and Control once a client tick and acts on each going down or up.
     *
     * @param event the tick
     */
    @Subscribe
    public void onClientTick(ClientTick event) {
        boolean shift = keyManager.isKeyPressed(KeyEvent.VK_SHIFT);
        boolean ctrl = keyManager.isKeyPressed(KeyEvent.VK_CONTROL);
        if (ctrl != ctrlDown) {
            ctrlDown = ctrl;
            keyChanged(KeyEvent.VK_CONTROL, ctrl);
        }
        if (shift != shiftDown) {
            shiftDown = shift;
            keyChanged(KeyEvent.VK_SHIFT, shift);
        }
    }

    /**
     * Applies what a key going down or up asks of the delay.
     *
     * @param keyCode the key
     * @param down    true if it went down
     */
    private void keyChanged(int keyCode, boolean down) {
        AntiDragKeys.Action action = down
                ? keys.pressed(keyCode, config.onShiftOnly(), config.disableOnCtrl())
                : keys.released(keyCode, config.onShiftOnly(), config.disableOnCtrl());
        if (action == AntiDragKeys.Action.SET) {
            setDragDelay();
        } else if (action == AntiDragKeys.Action.RESET) {
            resetDragDelay();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!CONFIG_GROUP.equals(event.getGroup())) {
            return;
        }
        clientThread.invoke(() -> {
            keys.settingsChanged(config.onShiftOnly(), config.disableOnCtrl());
            if (config.onShiftOnly()) {
                resetDragDelay();
            } else {
                setDragDelay();
            }
        });
    }

    @Subscribe
    public void onFocusChanged(FocusChanged event) {
        if (!event.isFocused()) {
            keys.clear();
            resetDragDelay();
        } else if (!config.onShiftOnly()) {
            setDragDelay();
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (!isOverriding()) {
            return;
        }
        int group = event.getGroupId();
        if (group == InterfaceID.BANKMAIN || group == InterfaceID.BANKSIDE || group == InterfaceID.SHARED_BANK
                || group == InterfaceID.SHARED_BANK_SIDE) {
            setBankDragDelay(config.dragDelay());
        } else if (group == InterfaceID.INVENTORY) {
            setInvDragDelay(config.dragDelay());
        } else if (group == InterfaceID.RUNE_POUCH) {
            setRunePouchDragDelay(config.dragDelay());
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        int script = event.getScriptId();
        if (script == ScriptID.INVENTORY_DRAWITEM) {
            Widget child = client.getScriptActiveWidget();
            if (child != null && (child.getParentId() == InterfaceID.Bankmain.ITEMS
                    || child.getParentId() == InterfaceID.Inventory.ITEMS)) {
                // The game's own [clientscript,inventory_antidrag_update]
                child.setOnMouseRepeatListener((Object[]) null);
                if (isOverriding()) {
                    child.setDragDeadTime(config.dragDelay());
                }
            }
        } else if (script == ScriptID.INTERFACE_INV_DRAW_SLOT_BIG) {
            Widget child = client.getScriptActiveWidget();
            if (child != null && child.getParentId() == InterfaceID.EquipmentSide.ITEMS && isOverriding()) {
                child.setDragDeadTime(config.dragDelay());
            }
        } else if (script == ScriptID.RAIDS_STORAGE_PRIVATE_ITEMS) {
            if (isOverriding()) {
                setCoxDragDelay(config.dragDelay());
            }
        } else if (script == ScriptID.BANK_DEPOSITBOX_INIT) {
            if (isOverriding()) {
                setBankDragDelay(config.dragDelay());
            }
        } else if (script == ScriptID.SEED_VAULT_BUILD) {
            if (isOverriding()) {
                setSeedVaultDragDelay(config.dragDelay());
            }
        }
    }

    private boolean isOverriding() {
        return keys.isOverriding(config.onShiftOnly());
    }

    private void applyDragDelay(int component, int delay) {
        Widget widget = client.getWidget(component);
        if (widget != null) {
            for (Widget item : widget.getDynamicChildren()) {
                item.setDragDeadTime(delay);
            }
        }
    }

    private void setBankDragDelay(int delay) {
        applyDragDelay(InterfaceID.Bankmain.ITEMS, delay);
        applyDragDelay(InterfaceID.Bankside.ITEMS, delay);
        applyDragDelay(InterfaceID.SharedBank.ITEMS, delay);
        applyDragDelay(InterfaceID.SharedBankSide.ITEMS, delay);
        applyDragDelay(InterfaceID.Bankside.WORNOPS, delay);
        applyDragDelay(InterfaceID.BankDepositbox.INVENTORY, delay);
        applyDragDelay(InterfaceID.RaidsStoragePrivate.ITEMS, delay);
    }

    private void setInvDragDelay(int delay) {
        applyDragDelay(InterfaceID.Inventory.ITEMS, delay);
        applyDragDelay(InterfaceID.EquipmentSide.ITEMS, delay);
    }

    private void setCoxDragDelay(int delay) {
        applyDragDelay(InterfaceID.RaidsStoragePrivate.ITEMS, delay);
    }

    private void setRunePouchDragDelay(int delay) {
        applyDragDelay(InterfaceID.RunePouch.POUCH, delay);
    }

    private void setSeedVaultDragDelay(int delay) {
        applyDragDelay(InterfaceID.SeedVault.OBJ_LIST, delay);
        applyDragDelay(InterfaceID.SeedVault.TEXT_LIST, delay);
    }

    private void setDragDelay() {
        int delay = config.dragDelay();
        log.fine("Anti Drag: delay set to " + delay);
        setInvDragDelay(delay);
        setBankDragDelay(delay);
        setCoxDragDelay(delay);
        setSeedVaultDragDelay(delay);
        setRunePouchDragDelay(delay);
    }

    private void resetDragDelay() {
        log.fine("Anti Drag: delay reset to " + DEFAULT_DELAY);
        setInvDragDelay(DEFAULT_DELAY);
        setBankDragDelay(DEFAULT_DELAY);
        setCoxDragDelay(DEFAULT_DELAY);
        setSeedVaultDragDelay(DEFAULT_DELAY);
        setRunePouchDragDelay(DEFAULT_DELAY);
    }
}
