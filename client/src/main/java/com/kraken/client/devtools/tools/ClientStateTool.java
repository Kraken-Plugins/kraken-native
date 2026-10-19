package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.game.ClientState;
import com.kraken.api.game.ClientState.ProfileType;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.ToolOption;
import com.kraken.client.devtools.view.FactsView;
import com.kraken.client.devtools.view.ToolView;
import com.kraken.client.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What {@code osrs.ClientState} reports about the client as a whole: game state, login, tick, profile type and how many
 * vars it holds. A quick check that the scalar reads line up with what you see in game.
 */
final class ClientStateTool extends DevTool<ClientStateTool.Snapshot> {
    record Snapshot(int gameState, boolean loggedIn, int tick, ProfileType profile, int worldFlags, boolean statsSent,
                    int varps, int intVarcs, int stringVarcs, boolean varcsMapped) {
    }

    private final ToolOption gameState = option("gameState", "Game state", true);
    private final ToolOption tick = option("tick", "Tick", true);
    private final ToolOption profile = option("profile", "Profile type", false);
    private final ToolOption vars = option("vars", "Var counts", false);

    ClientStateTool() {
        super("clientState", "Client state", Category.STATE, MaterialDesignG.GAUGE,
                "Game state, login, tick count, profile type and world flags, as ClientState reads them.");
    }

    @Override
    public Support overlaySupport() {
        return Support.READY;
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected Snapshot read(KrakenApi api) {
        ClientState state = api.clientState();
        boolean varcs = state.hasVarcs();
        // Varc maps are only read while the window (or that overlay row) shows them: string varcs cost a read each
        boolean countVarcs = varcs && (isWindowOpen() || vars.isOn());
        return new Snapshot(state.getGameState(), state.isLoggedIn(), state.getTickCount(), state.getProfileType(),
                state.getWorldFlags(), state.areStatsTransmitted(), state.getVarpCount(),
                countVarcs ? state.getIntegerVarcs().size() : 0, countVarcs ? state.getStringVarcs().size() : 0, varcs);
    }

    @Override
    protected void paintOverlay(Snapshot snapshot, OverlayContext overlay) {
        overlay.infoPanel("Client state", facts(snapshot, true));
    }

    @Override
    protected ToolView<Snapshot> createView() {
        return new FactsView<>(snapshot -> facts(snapshot, false));
    }

    private Map<String, String> facts(Snapshot s, boolean onlyEnabled) {
        Map<String, String> rows = new LinkedHashMap<>();
        if (!onlyEnabled || gameState.isOn()) {
            rows.put("Game state", s.gameState() + " · " + gameStateName(s.gameState()));
            rows.put("Logged in", s.loggedIn() ? "Yes" : "No");
        }
        if (!onlyEnabled || tick.isOn()) {
            rows.put("Tick", String.valueOf(s.tick()));
        }
        if (!onlyEnabled || profile.isOn()) {
            rows.put("Profile type", s.profile().name());
            rows.put("World flags", String.format("0x%08x", s.worldFlags()));
        }
        if (!onlyEnabled) {
            rows.put("Stats sent", s.statsSent() ? "Yes" : "Not yet");
        }
        if (!onlyEnabled || vars.isOn()) {
            rows.put("Varps", String.valueOf(s.varps()));
            rows.put("Varcs", s.varcsMapped() ? s.intVarcs() + " int · " + s.stringVarcs() + " string" : "Not in mapping");
        }
        return rows;
    }

    /** A label for the raw game state; the numbers the client is known to use. */
    static String gameStateName(int state) {
        return switch (state) {
            case -1 -> "Unreadable";
            case ClientState.GAME_STATE_TITLE -> "Title screen";
            case ClientState.GAME_STATE_LOGGING_IN -> "Logging in";
            case ClientState.GAME_STATE_LOADING -> "Loading";
            case ClientState.GAME_STATE_LOGGED_IN -> "Logged in";
            case ClientState.GAME_STATE_CONNECTION_LOST -> "Connection lost";
            case ClientState.GAME_STATE_HOPPING -> "Hopping";
            default -> "Other";
        };
    }
}
