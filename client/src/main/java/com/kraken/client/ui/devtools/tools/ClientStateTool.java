package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.KrakenApi;
import com.kraken.api.ProfileType;
import com.kraken.api.Skill;
import com.kraken.api.WorldType;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.FactsView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * What the client reports about itself as a whole: game state, login, tick, profile type, world type and how many
 * vars it holds. A quick check that the scalar reads line up with what you see in game.
 */
final class ClientStateTool extends DevTool<ClientStateTool.Snapshot> {
    /**
     * The client's state as one poll read it.
     *
     * @param gameState  the game state
     * @param loggedIn   whether you're logged in
     * @param tick       the game tick
     * @param profile    the profile type
     * @param worlds     the kinds of world
     * @param statsSent  whether the server has sent the stats
     * @param varps      the number of varps, or -1 when not counted
     * @param intVarcs   the number of int varcs, or -1 when not counted
     * @param stringVarcs the number of string varcs, or -1 when not counted
     */
    record Snapshot(GameState gameState, boolean loggedIn, int tick, ProfileType profile, EnumSet<WorldType> worlds,
                    boolean statsSent, int varps, int intVarcs, int stringVarcs) {
    }

    private final ToolOption gameState = option("gameState", "Game state", true);
    private final ToolOption tick = option("tick", "Tick", true);
    private final ToolOption profile = option("profile", "Profile type", false);
    private final ToolOption vars = option("vars", "Var counts", false);

    ClientStateTool() {
        super("clientState", "Client state", Category.STATE, MaterialDesignG.GAUGE,
                "Game state, login, tick count, profile type and world type.");
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
        Client client = api.client();
        // Var counts are only read while the window (or that overlay row) shows them: a varp or varc read costs a read
        boolean count = isWindowOpen() || vars.isOn();
        int varps = -1;
        int intVarcs = -1;
        int stringVarcs = -1;
        if (count) {
            varps = client.getVarps().length;
            Map<Integer, Object> varcs = client.getVarcMap();
            intVarcs = (int) varcs.values().stream().filter(v -> v instanceof Integer).count();
            stringVarcs = (int) varcs.values().stream().filter(v -> v instanceof String).count();
        }
        return new Snapshot(client.getGameState(), client.isLoggedIn(), client.getTickCount(),
                client.getProfileType(), client.getWorldType(),
                client.getRealSkillLevel(Skill.ATTACK) != -1, varps, intVarcs, stringVarcs);
    }

    @Override
    protected void paintOverlay(Snapshot snapshot, OverlayContext overlay) {
        overlay.infoPanel("Client state", facts(snapshot, true));
    }

    @Override
    protected ToolView<Snapshot> createView() {
        return new FactsView<>(snapshot -> facts(snapshot, false));
    }

    /**
     * The rows to show.
     *
     * @param s           the state
     * @param onlyEnabled apply the options (the window always shows everything)
     * @return the rows, in order
     */
    private Map<String, String> facts(Snapshot s, boolean onlyEnabled) {
        Map<String, String> rows = new LinkedHashMap<>();
        if (!onlyEnabled || gameState.isOn()) {
            rows.put("Game state", s.gameState().getState() + " · " + gameStateName(s.gameState()));
            rows.put("Logged in", s.loggedIn() ? "Yes" : "No");
        }
        if (!onlyEnabled || tick.isOn()) {
            rows.put("Tick", String.valueOf(s.tick()));
        }
        if (!onlyEnabled || profile.isOn()) {
            rows.put("Profile type", s.profile().name());
            rows.put("World type", worldTypes(s.worlds()));
        }
        if (!onlyEnabled) {
            rows.put("Stats sent", s.statsSent() ? "Yes" : "Not yet");
        }
        if (!onlyEnabled || vars.isOn()) {
            rows.put("Varps", s.varps() < 0 ? "Not counted" : String.valueOf(s.varps()));
            rows.put("Varcs", s.intVarcs() < 0 ? "Not counted"
                    : s.intVarcs() + " int · " + s.stringVarcs() + " string");
        }
        return rows;
    }

    /**
     * The world type names, joined.
     *
     * @param worlds the world's types
     * @return e.g. "MEMBERS, PVP", or "None"
     */
    private static String worldTypes(EnumSet<WorldType> worlds) {
        if (worlds.isEmpty()) {
            return "None";
        }
        StringJoiner joined = new StringJoiner(", ");
        for (WorldType type : worlds) {
            joined.add(type.name());
        }
        return joined.toString();
    }

    /**
     * A label for the game state.
     *
     * @param state the game state
     * @return a short name for it
     */
    static String gameStateName(GameState state) {
        return switch (state) {
            case UNKNOWN -> "Unreadable";
            case STARTING -> "Starting";
            case LOGIN_SCREEN, LOGIN_SCREEN_AUTHENTICATOR -> "Title screen";
            case LOGGING_IN -> "Logging in";
            case LOADING -> "Loading";
            case LOGGED_IN -> "Logged in";
            case CONNECTION_LOST -> "Connection lost";
            case HOPPING -> "Hopping";
        };
    }
}
