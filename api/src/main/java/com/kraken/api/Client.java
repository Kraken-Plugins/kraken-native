package com.kraken.api;

import com.kraken.api.action.Actions;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.definition.ObjectComposition;
import com.kraken.api.definition.VarbitComposition;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.highlight.Highlights;
import com.kraken.api.menu.MenuEdits;
import com.kraken.api.internal.reader.ActorReader;
import com.kraken.api.internal.reader.ChatReader;
import com.kraken.api.internal.ConfigTypes;
import com.kraken.api.internal.reader.GameReader;
import com.kraken.api.internal.reader.GraphicsObjectReader;
import com.kraken.api.internal.reader.InventoryReader;
import com.kraken.api.internal.MapCoord;
import com.kraken.api.internal.reader.MenuReader;
import com.kraken.api.internal.reader.ProjectileReader;
import com.kraken.api.internal.ScriptComponents;
import com.kraken.api.internal.ScriptStack;
import com.kraken.api.internal.VarcValues;
import com.kraken.api.internal.reader.ScriptReader;
import com.kraken.api.internal.reader.SceneReader;
import com.kraken.api.internal.reader.WidgetReader;
import com.kraken.api.internal.widget.ComponentWrites;
import com.kraken.api.item.ItemContainer;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;
import com.kraken.api.render.EntityVisibility;
import com.kraken.api.scene.GraphicsObject;
import com.kraken.api.scene.Projectile;
import com.kraken.api.scene.Scene;
import com.kraken.api.scene.WorldView;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetNode;
import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The game client: the root of the API. Get it from {@code GameSession.client()}.
 *
 * <p>Every getter follows the mapped offset chains in the live process, replaying what the client's own Lua handlers
 * read; a getter's Javadoc names the Lua function it mirrors. The API reads, so input, models and setters are not
 * exposed. A few things it does change, through the client's native hook: what the client highlights
 * ({@link #getHighlights()}), the menu actions it runs ({@link #getActions()}, {@link #menuAction}, and the world hops
 * {@link #openWorldHopper} and {@link #hopToWorld} make of them), the rows of the
 * right-click menu ({@link #getMenuEdits()}, {@link #createMenuEntry}, {@link #setMenuEntries} and the
 * {@link MenuEntry} setters), which entities it draws ({@link #getEntityVisibility()}), and a few fields of interface
 * components (the {@link Widget} setters). Reads are tolerant: an unreadable or logged-out state gives -1, false, "",
 * null or an empty list rather than an exception.
 *
 * <p>It keeps one handle per actor ({@link #npc}, {@link #player}), so asking twice for the same actor gives the same
 * object, and a handle's identity holds as long as the actor does. A handle is replaced when another actor takes its
 * index, not when a read finds the index empty, so one failed read doesn't cost a handle its identity.
 *
 * <p>Read on any thread. Each call is one or more reads of the game process, so code that needs a value several times
 * in a row should keep it.
 */
public final class Client extends GameReader {

    /**
     * The game state the client reports while logged in, as its {@code setGameState} switches on it.
     */
    private static final int LOGGED_IN = 30;

    private static final int MAX_PLAYERS = 2048;
    private static final int MAX_NPCS = 65536;
    private static final int MAX_VARPS = 1 << 16;
    private static final int MAX_VARCS = 1 << 16;

    /**
     * Variant tags of a varc entry's value (eastl::variant index + 1, as the client compares it).
     */
    private static final int VARC_INT = 1;
    private static final int VARC_STRING = 2;

    /**
     * The config types (definitions) this client decodes and caches. Internal plumbing for the API's own packages.
     */
    @Getter
    private final ConfigTypes configTypes;

    /**
     * The reader the actor handles read through. Internal plumbing for the API's own packages.
     */
    @Getter
    private final ActorReader actorReader;

    /**
     * The reader the world view, scene and tiles read through. Internal plumbing for the API's own packages.
     */
    @Getter
    private final SceneReader sceneReader;
    private final InventoryReader inventories;

    /**
     * The reader the projectiles and their events read through. Internal plumbing for the API's own packages.
     */
    @Getter
    private final ProjectileReader projectileReader;

    /**
     * The reader the graphics objects read through. Internal plumbing for the API's own packages.
     */
    @Getter
    private final GraphicsObjectReader graphicsObjectReader;

    /**
     * The reader the widgets and the interface events read through. Internal plumbing for the API's own packages.
     */
    @Getter
    private final WidgetReader widgetReader;
    private final ScriptReader scripts;

    /**
     * The stacks a script event captured, on the thread posting it while its handlers run.
     */
    private final ThreadLocal<ScriptStack> scriptStack = new ThreadLocal<>();

    /**
     * The active and dot components a script event captured, on the thread posting it while its handlers run.
     */
    private final ThreadLocal<ScriptComponents> scriptComponents = new ThreadLocal<>();

    /**
     * The reader the menu and the menu events read through. Internal plumbing for the API's own packages.
     */
    @Getter
    private final MenuReader menuReader;

    /**
     * The reader the chat events read through. Internal plumbing for the API's own packages.
     */
    @Getter
    private final ChatReader chatReader;

    /**
     * Whether the game has the keyboard focus; never, until {@code GameSession.attach} sets the check.
     */
    private volatile BooleanSupplier focus = () -> false;

    /**
     * The client's own highlights: model and tile outlines and fills drawn inside the 3D scene. Changes reach the
     * client while its native hook is connected.
     */
    @Getter
    private final Highlights highlights;

    /**
     * Changes to the right-click menu, applied by the client's native hook each time the client rebuilds the menu:
     * the rules behind {@link #createMenuEntry}, {@link #setMenuEntries} and the {@link MenuEntry} setters, and
     * the rules plugins keep themselves.
     */
    @Getter
    private final MenuEdits menuEdits;

    /**
     * Menu actions run without the menu, and the mouse click that goes with one. They reach the client while its
     * native hook is connected.
     */
    @Getter
    private final Actions actions;

    /**
     * Which players, NPCs, projectiles and graphics objects the client leaves undrawn, by plugins' rules the client's
     * native hook applies every frame.
     */
    @Getter
    private final EntityVisibility entityVisibility;

    /**
     * The top-level world: the main map, its actors and its sceneReader.
     */
    @Getter
    private final WorldView topLevelWorldView;

    private final Map<Integer, NPC> npcs = new ConcurrentHashMap<>();
    private final Map<Integer, Player> players = new ConcurrentHashMap<>();

    private final int cGameState;
    private final int cLoginToken;
    private final int cTick;
    private final int gameStateLoggedIn;
    private final int cWorldFlags;
    private final int cCurrentWorld;
    private final long cycleRva;
    private final int cActorSteps;
    private final int cDestination;
    private final int dX;
    private final int dZ;

    private final int cSkillLevel;
    private final int cSkillBase;
    private final int cSkillXp;
    private final int cStatsValid;
    private final int statCount;

    private final long varpsRva;
    private final long varpCountRva;
    private final long varpsBitsRva;
    private final int cVarcs;
    private final int varcsBegin;
    private final int varcsEnd;
    private final int varcStride;
    private final int varcKey;
    private final int varcValue;
    private final int varcType;

    private final int cPlayerCount;
    private final int cPlayerIndices;
    private final int wNpcIndices;
    private final int wNpcCount;

    private final int cCameraPos;
    private final int cCameraLookAt;
    private final int cCameraAngles;
    private final int cScale;
    private final int cZoom;
    private final long cameraControlledRva;
    private final long cameraTargetRva;

    private final int cRenderer;
    private final int cTopWorld;
    private final int wScene;
    private final int scTrig;
    private final int rCanvasWidth;
    private final int rCanvasHeight;
    private final int rScreenWidth;
    private final int rScreenHeight;
    private final int rViewport;
    private final int vCenterX;
    private final int vCenterY;
    private final int vOffsetX;
    private final int vOffsetY;
    private final boolean canProject;

    /**
     * Builds the client over a process image, resolving every offset it needs once. {@code GameSession.attach} makes
     * the one for the attached process; making another is for tests and tools that read a memory image of their own.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     * @throws com.kraken.api.mapping.Mapping.MissingMappingException if the mapping lacks a key every build has
     */
    public Client(Memory memory, Mapping mapping) {
        super(memory, mapping);
        this.configTypes = new ConfigTypes(memory, mapping, this);
        this.actorReader = new ActorReader(memory, mapping, configTypes);
        this.sceneReader = new SceneReader(memory, mapping, configTypes, actorReader);
        this.inventories = new InventoryReader(memory, mapping, configTypes);
        this.projectileReader = new ProjectileReader(memory, mapping, this);
        this.graphicsObjectReader = new GraphicsObjectReader(memory, mapping, this, sceneReader);
        this.widgetReader = new WidgetReader(memory, mapping);
        this.scripts = new ScriptReader(memory, mapping);
        this.menuReader = new MenuReader(memory, mapping, this);
        this.chatReader = new ChatReader(memory, mapping);
        this.topLevelWorldView = new WorldView(this);
        this.highlights = new Highlights(mapping);
        this.actions = new Actions(this, mapping);
        this.menuEdits = new MenuEdits(this, mapping);
        this.entityVisibility = new EntityVisibility(this, mapping);

        this.cGameState = mapping.offset("Client.gameState");
        this.cLoginToken = mapping.offset("Client.loginToken");
        this.cTick = mapping.offset("Client.tickCount");
        this.gameStateLoggedIn = mapping.enumValue("gameState", "LOGGED_IN", LOGGED_IN);
        this.cWorldFlags = optionalOffset("Client.worldFlags");
        this.cCurrentWorld = optionalOffset("Client.currentWorld");
        this.cycleRva = optionalGlobal("cycle");
        this.cActorSteps = optionalOffset("Client.actorSteps");
        this.cDestination = optionalOffset("Client.destination");
        this.dX = optionalOffset("Destination.x");
        this.dZ = optionalOffset("Destination.z");

        this.cSkillLevel = mapping.offset("Client.skillLevel");
        this.cSkillBase = mapping.offset("Client.skillBase");
        this.cSkillXp = mapping.offset("Client.skillXp");
        this.cStatsValid = mapping.offset("Client.statsValidGate");
        // The client's stat getters accept ids up to skillIndexMax inclusive, but each array holds skillIndexMax
        // entries (the next array starts right after), so the last id the client accepts reads a neighbouring array
        this.statCount = mapping.offset("Client.skillIndexMax");

        this.varpsRva = mapping.global("varps");
        this.varpCountRva = optionalGlobal("varpCount");
        this.varpsBitsRva = optionalGlobal("varpsBits");
        this.cVarcs = optionalOffset("Client.varcs");
        this.varcsBegin = optionalOffset("Varcs.entries");
        this.varcsEnd = optionalOffset("Varcs.entriesEnd");
        this.varcStride = optionalOffset("VarcEntry.size");
        this.varcKey = optionalOffset("VarcEntry.key");
        this.varcValue = optionalOffset("VarcEntry.value");
        this.varcType = optionalOffset("VarcEntry.type");

        this.cPlayerCount = mapping.offset("Client.playerIndexCount");
        this.cPlayerIndices = mapping.offset("Client.playerIndices");
        this.wNpcIndices = mapping.offset("World.npcIndices");
        this.wNpcCount = mapping.offset("World.npcIndexCount");

        this.cCameraPos = mapping.offset("Client.camera.pos");
        this.cCameraLookAt = optionalOffset("Client.camera.lookAt");
        this.cCameraAngles = mapping.offset("Client.camera.angles");
        this.cScale = optionalOffset("Client.zoomA");
        this.cZoom = optionalOffset("Client.zoomB");
        this.cameraControlledRva = optionalGlobal("cameraControlled");
        this.cameraTargetRva = optionalGlobal("cameraTarget");

        this.cRenderer = mapping.offset("Client.renderer");
        this.cTopWorld = mapping.offset("Client.topLevelWorld");
        this.wScene = optionalOffset("World.scene");
        this.scTrig = optionalOffset("Scene.cameraTrig");
        this.rCanvasWidth = mapping.offset("Renderer.canvasWidth");
        this.rCanvasHeight = mapping.offset("Renderer.canvasHeight");
        this.rScreenWidth = optionalOffset("Renderer.screenWidth");
        this.rScreenHeight = optionalOffset("Renderer.screenHeight");
        this.rViewport = optionalOffset("Renderer.viewport");
        this.vCenterX = optionalOffset("Viewport.centerX");
        this.vCenterY = optionalOffset("Viewport.centerY");
        this.vOffsetX = optionalOffset("Viewport.offsetX");
        this.vOffsetY = optionalOffset("Viewport.offsetY");
        this.canProject = cScale >= 0 && wScene >= 0 && scTrig >= 0 && rScreenWidth >= 0 && rScreenHeight >= 0
                && rViewport >= 0 && vCenterX >= 0 && vCenterY >= 0 && vOffsetX >= 0 && vOffsetY >= 0;
    }

    /**
     * The client's game state.
     *
     * @return the state; {@link GameState#UNKNOWN} if it can't be read
     */
    public GameState getGameState() {
        return GameState.of(rawGameState());
    }

    /**
     * Server ticks processed since login (Lua's {@code ClientState.getTickCount}).
     *
     * @return the tick count, or 0 if it can't be read
     */
    public int getTickCount() {
        return Math.max(tickCount(), 0);
    }

    /**
     * The tick counter, telling a failed read apart from a counter at 0: logout resets the counter to 0, and the
     * event source must not take a read that failed for that reset.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @return the tick count, or -1 if it can't be read
     */
    public int tickCount() {
        long client = clientBase();
        return client == 0 ? -1 : safeInt(client + cTick, -1);
    }

    /**
     * The client's count of actor steps: one per client cycle while logged in, the step that counts every actor's
     * overhead text timer down by one. The overhead text events measure timers against it.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @return the count, or -1 if it can't be read or this build's mapping lacks it
     */
    public int actorSteps() {
        long client = clientBase();
        return client == 0 || cActorSteps < 0 ? -1 : safeInt(client + cActorSteps, -1);
    }

    /**
     * The client's cycle counter: one per 20 ms client cycle, the clock head bars, projectiles and spawn cycles are
     * measured on.
     *
     * @return the cycle, or -1 if it can't be read
     */
    public int getGameCycle() {
        return cycleRva < 0 ? -1 : safeInt(memory.moduleBase() + cycleRva, -1);
    }

    /**
     * Whether the game has the keyboard focus: the foreground window's focus is on one of the game's windows.
     * Alt-tabbing away, clicking another window, or clicking a control beside the game in a window it is embedded in
     * takes it away. Windows' focus state, not the client's: the client keeps none.
     *
     * @return true if keys typed now go to the game; false for a client not attached to a running game
     */
    public boolean isFocused() {
        return focus.getAsBoolean();
    }

    /**
     * Sets how {@link #isFocused()} finds out.
     * Internal: {@code GameSession.attach} sets it for the attached process; tests set their own.
     *
     * @param focus the check
     */
    public void setFocusSource(BooleanSupplier focus) {
        this.focus = focus;
    }

    /**
     * The kinds of world the client is on, from the world flags.
     *
     * @return the world's types; empty if the flags can't be read
     */
    public EnumSet<WorldType> getWorldType() {
        return WorldType.fromMask(worldFlags());
    }

    /**
     * The world the client is on: the number the cs2 command {@code map_world} gives scripts, e.g. 302. No Lua
     * function reads it.
     *
     * @return the world's number, or -1 if it can't be read or this build's mapping lacks it
     */
    public int getWorld() {
        long client = clientBase();
        return client == 0 || cCurrentWorld < 0 ? -1 : safeInt(client + cCurrentWorld, -1);
    }

    /**
     * Opens the world switcher as its button on the logout panel does: a component op the server answers by opening
     * the switcher in the panel's place, from the next tick or so. The switcher has to be open for
     * {@link #hopToWorld} to work.
     */
    public void openWorldHopper() {
        actions.menuAction(-1, InterfaceID.Logout.WORLD_SWITCHER, MenuAction.CC_OP, 1);
    }

    /**
     * Hops to a world as clicking its row in the open world switcher does: the "Switch" op on the switcher's row of
     * buttons, with the world's number as the slot. The server makes the hop, so it can still refuse it (during
     * combat, say), and the client sends nothing unless the world switcher is open ({@link #openWorldHopper}).
     *
     * @param world the world; only its id is used
     */
    public void hopToWorld(World world) {
        actions.menuAction(world.getId(), InterfaceID.Worldswitcher.BUTTONS, MenuAction.CC_OP, 1);
    }

    /**
     * Whether the client has a login session and is in the world. Mirrors Lua {@code ClientState.isLoggedIn}.
     *
     * @return true when logged in
     */
    public boolean isLoggedIn() {
        long client = clientBase();
        if (client == 0) {
            return false;
        }
        return safeLong(client + cLoginToken, -1) != -1 && safeInt(client + cGameState, -1) == gameStateLoggedIn;
    }

    /**
     * The kind of world, as the client decodes its world flags for Lua: beta, then deadman, PvP arena, quest
     * speedrunning and seasonal, the later ones winning. Mirrors Lua {@code ClientState.getProfileType}.
     *
     * @return the profile type; {@link ProfileType#STANDARD} if the flags can't be read
     */
    public ProfileType getProfileType() {
        int flags = worldFlags();
        ProfileType type = (flags >>> 16 & 1) != 0 ? ProfileType.BETA : ProfileType.STANDARD;
        if ((flags & 1 << 29) != 0) {
            type = ProfileType.DEADMAN;
        }
        if ((flags & 1 << 6) != 0) {
            type = ProfileType.PVP_ARENA;
        }
        if ((flags & 1 << 8) != 0) {
            type = ProfileType.QUEST_SPEEDRUNNING;
        }
        if ((flags & 1 << 30) != 0) {
            type = ProfileType.SEASONAL;
        }
        return type;
    }

    /**
     * The local player.
     *
     * @return its handle, or null when logged out
     */
    public Player getLocalPlayer() {
        int index = actorReader.localPlayerIndex();
        return index < 0 ? null : player(index);
    }

    /**
     * Where the local player is walking to: the tile the client marks with its flag after a click on the scene or on
     * something to interact with. The client clears it once the local player stands on it.
     *
     * @return the centre of the destination tile, or null when there is none or it can't be read
     */
    public LocalPoint getLocalDestinationLocation() {
        long client = clientBase();
        if (client == 0 || cDestination < 0 || dX < 0 || dZ < 0) {
            return null;
        }
        long destination = safeLong(client + cDestination, 0);
        if (destination == 0) {
            return null;
        }
        int x = safeInt(destination + dX, 0);
        int z = safeInt(destination + dZ, 0);
        // The client clears the destination by setting it to 0, the scene's edge, which can't be walked to
        if (x <= 0 || z <= 0) {
            return null;
        }
        return LocalPoint.fromScene(x, z, topLevelWorldView);
    }

    /**
     * The local player's follower, such as a pet.
     *
     * @return the follower's handle, or null if the local player has none loaded
     */
    public NPC getFollower() {
        for (NPC npc : npcList()) {
            if (npc.isFollower() && npc.isLocalFollower()) {
                return npc;
            }
        }
        return null;
    }

    /**
     * A world view by id.
     *
     * @param id the view's id; {@link WorldView#TOPLEVEL} for the top-level world
     * @return the view, or null for an id that isn't loaded (sub-worlds aren't wrapped yet)
     */
    public WorldView getWorldView(int id) {
        return id == WorldView.TOPLEVEL ? topLevelWorldView : null;
    }

    /**
     * The NPCs loaded in the top-level world. Same as {@code getTopLevelWorldView().npcs()}.
     *
     * @return their handles; the same handle objects from call to call while each NPC stays loaded
     */
    public List<NPC> getNpcs() {
        return npcList();
    }

    /**
     * The players loaded. Same as {@code getTopLevelWorldView().players()}.
     *
     * @return their handles; the same handle objects from call to call while each player stays loaded
     */
    public List<Player> getPlayers() {
        return playerList();
    }

    /**
     * The index of the first NPC whose name is exactly {@code name}, walking each loaded world's NPC table (sub-worlds
     * such as boats included). Mirrors Lua {@code ClientOp.getNpcIdByName}, which differs on purpose: the native
     * handler looks its match up again by uid instead of index, so in Lua it returns -1 for almost every NPC.
     * {@code getTopLevelWorldView().npcs().byIndex(index)} gives the handle of a top-level NPC.
     *
     * @param name the exact name to match (case-sensitive)
     * @return the NPC's index, or -1 if none matches
     */
    public int getNpcIndexByName(String name) {
        return actorReader.npcIdByName(name);
    }

    /**
     * The index of every NPC in each loaded world's active list whose name is exactly {@code name}. Mirrors Lua
     * {@code ClientOp.getNpcIdByNameAll}.
     *
     * @param name the exact name to match (case-sensitive)
     * @return the NPCs' indices, empty if none matches
     */
    public int[] getNpcIndicesByName(String name) {
        return actorReader.npcIdsByName(name);
    }

    /**
     * The index of the first player whose normalised name equals {@code name} lower-cased (ASCII letters only, as the
     * client's {@code tolower} does), walking each loaded world's player table. Mirrors Lua
     * {@code ClientOp.getPlayerIdByName}.
     *
     * @param name the player's name
     * @return the player's index, or -1 if none matches
     */
    public int getPlayerIndexByName(String name) {
        return actorReader.playerIdByName(name);
    }

    /**
     * The top-level world's sceneReader. Same as {@code getTopLevelWorldView().getScene()}.
     *
     * @return the scene
     */
    public Scene getScene() {
        return topLevelWorldView.getScene();
    }

    /**
     * A skill's level from its XP (Lua's {@code ClientState.getStatBaseLevel}).
     *
     * @param skill the skill
     * @return the base level, or -1 before the server sends the stats
     */
    public int getRealSkillLevel(Skill skill) {
        return stat(cSkillBase, skill.ordinal());
    }

    /**
     * A skill's current level, boosted or drained (Lua's {@code ClientState.getStatEffectiveLevel}).
     *
     * @param skill the skill
     * @return the current level, or -1 before the server sends the stats
     */
    public int getBoostedSkillLevel(Skill skill) {
        return stat(cSkillLevel, skill.ordinal());
    }

    /**
     * A skill's XP (Lua's {@code ClientState.getStatXP}).
     *
     * @param skill the skill
     * @return the XP, or -1 before the server sends the stats
     */
    public int getSkillExperience(Skill skill) {
        return stat(cSkillXp, skill.ordinal());
    }

    /**
     * Every skill's level from its XP, in one read.
     *
     * @return the base levels indexed by {@link Skill#ordinal()}; all -1 before the server sends the stats
     */
    public int[] getRealSkillLevels() {
        Stats stats = stats();
        return stats == null ? missingStats() : stats.base();
    }

    /**
     * Every skill's current level, in one read.
     *
     * @return the current levels indexed by {@link Skill#ordinal()}; all -1 before the server sends the stats
     */
    public int[] getBoostedSkillLevels() {
        Stats stats = stats();
        return stats == null ? missingStats() : stats.level();
    }

    /**
     * Every skill's XP, in one read.
     *
     * @return the XP indexed by {@link Skill#ordinal()}; all -1 before the server sends the stats
     */
    public int[] getSkillExperiences() {
        Stats stats = stats();
        return stats == null ? missingStats() : stats.xp();
    }

    /**
     * The total level: the sum of every skill's level from its XP.
     *
     * @return the total, or -1 before the server sends the stats
     */
    public int getTotalLevel() {
        Stats stats = stats();
        if (stats == null) {
            return -1;
        }
        int total = 0;
        for (int skill = 0; skill < Skill.values().length && skill < stats.base().length; skill++) {
            total += stats.base()[skill];
        }
        return total;
    }

    /**
     * A varbit's value (Lua's {@code ClientState.getVarbit}): {@code (varp >> lsb) & mask} from its definition, read
     * from the varp array the client's varbit reader uses (the one {@link #getVarpValue} reads). An id without a
     * definition reads bit 0 of varp 0, as in the client.
     *
     * @param varbitId the varbit id
     * @return its value; -1 if its definition or varp can't be read, or its varp is outside the varp array
     */
    public int getVarbitValue(int varbitId) {
        VarbitComposition type = configTypes.varbit(varbitId);
        if (type == null || varpsBitsRva < 0 || type.getIndex() >= varpCount()) {
            return -1;
        }
        long bits = safePointer(memory.moduleBase() + varpsBitsRva);
        if (bits == 0) {
            return -1;
        }
        try {
            return type.extract(memory.readInt(bits + type.getIndex() * 4L));
        } catch (MemoryException e) {
            return -1;
        }
    }

    /**
     * A varbit's definition: which varp it lives in and which bits of it.
     *
     * @param varbitId the varbit id
     * @return the definition ({@link VarbitComposition#DEFAULT} for an id without one, as in the client), or null if
     *         config types can't be read
     */
    public VarbitComposition getVarbit(int varbitId) {
        return configTypes.varbit(varbitId);
    }

    /**
     * A varp's value as the client holds it, including any change the client has made locally (the array varbits are
     * read from).
     *
     * @param varpId the varp id
     * @return its value, or -1 for an id outside the varp array or if it can't be read
     */
    public int getVarpValue(int varpId) {
        if (varpId < 0 || varpId >= varpCount() || varpsBitsRva < 0) {
            return -1;
        }
        long bits = safePointer(memory.moduleBase() + varpsBitsRva);
        return bits == 0 ? -1 : safeInt(bits + varpId * 4L, -1);
    }

    /**
     * A varp's value as the server last sent it (Lua's {@code ClientState.getVarp}).
     *
     * @param varpId the varp id
     * @return its value, or -1 for an id outside the varp array or if it can't be read
     */
    public int getServerVarpValue(int varpId) {
        if (varpId < 0 || varpId >= varpCount()) {
            return -1;
        }
        long varps = safePointer(memory.moduleBase() + varpsRva);
        return varps == 0 ? -1 : safeInt(varps + varpId * 4L, -1);
    }

    /**
     * Every varp as the client holds it (see {@link #getVarpValue}), in one read.
     *
     * @return the values indexed by varp id; empty if they can't be read
     */
    public int[] getVarps() {
        int count = varpCount();
        long bits = varpsBitsRva < 0 ? 0 : safePointer(memory.moduleBase() + varpsBitsRva);
        int[] values = count == 0 || bits == 0 ? null : readInts(bits, count);
        return values == null ? new int[0] : values;
    }

    /**
     * Every varp as the server last sent it, in one read.
     *
     * @return the values indexed by varp id; empty if they can't be read
     */
    public int[] getServerVarps() {
        int count = varpCount();
        long varps = safePointer(memory.moduleBase() + varpsRva);
        int[] values = count == 0 || varps == 0 ? null : readInts(varps, count);
        return values == null ? new int[0] : values;
    }

    /**
     * An integer varc's value (Lua's {@code ClientState.getIntegerVarc}, which gives -1 for an unset varc).
     *
     * @param varcId the varc id
     * @return its value, or 0 if it isn't set
     */
    public int getVarcIntValue(int varcId) {
        long entry = findVarc(varcId);
        if (entry == 0 || safeU8(entry + varcType, 0) != VARC_INT) {
            return 0;
        }
        return safeInt(entry + varcValue, 0);
    }

    /**
     * A string varc's value (Lua's {@code ClientState.getStringVarc}).
     *
     * @param varcId the varc id
     * @return its value, or "" if it isn't set
     */
    public String getVarcStrValue(int varcId) {
        long entry = findVarc(varcId);
        if (entry == 0 || safeU8(entry + varcType, 0) != VARC_STRING) {
            return "";
        }
        return structs.string(entry + varcValue);
    }

    /**
     * Every varc the client holds.
     *
     * @return varc id to its value (an {@code Integer} or a {@code String}), in id order; empty if the varcs can't be
     *         read
     */
    public Map<Integer, Object> getVarcMap() {
        Map<Integer, Object> out = new LinkedHashMap<>();
        long[] entries = varcEntries();
        ByteBuffer block = entries == null ? null : readBlock(entries[0], (int) (entries[1] * varcStride));
        if (block == null) {
            return out;
        }
        for (int i = 0; i < entries[1]; i++) {
            int at = i * varcStride;
            int id = block.getInt(at + varcKey);
            int type = block.get(at + varcType) & 0xff;
            if (type == VARC_INT) {
                out.put(id, block.getInt(at + varcValue));
            } else if (type == VARC_STRING) {
                out.put(id, structs.string(entries[0] + at + varcValue));
            }
        }
        return out;
    }

    /**
     * Internal: every int and string varc, in one read of the varc store, for the varc events. The read is strict: a
     * store, entry array or string text that can't be read gives null rather than part of the varcs, so a failed read
     * never looks like varcs being dropped.
     *
     * @return the varcs (empty maps for an empty store), or null if the mapping lacks the varc keys or the store can't
     *         be read
     */
    public VarcValues readVarcs() {
        long client = varcsMapped() ? clientBase() : 0;
        if (client == 0) {
            return null;
        }
        try {
            long store = memory.readPointer(client + cVarcs);
            if (store == 0) {
                return null;
            }
            long begin = memory.readPointer(store + varcsBegin);
            long size = memory.readPointer(store + varcsEnd) - begin;
            if (size < 0 || size % varcStride != 0 || size / varcStride > MAX_VARCS || (begin == 0 && size != 0)) {
                return null;
            }
            Map<Integer, Integer> ints = new HashMap<>();
            Map<Integer, String> strings = new HashMap<>();
            byte[] block = size == 0 ? new byte[0] : memory.read(begin, (int) size);
            ByteBuffer buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
            for (int at = 0; at < block.length; at += varcStride) {
                int id = buffer.getInt(at + varcKey);
                int type = block[at + varcType] & 0xff;
                if (type == VARC_INT) {
                    ints.put(id, buffer.getInt(at + varcValue));
                } else if (type == VARC_STRING) {
                    strings.put(id, structs.readString(block, begin, at + varcValue));
                }
            }
            return new VarcValues(ints, strings);
        } catch (MemoryException e) {
            return null;
        }
    }

    /**
     * The script engine's int stack. While a {@code ScriptPreFired} or {@code ScriptPostFired} handler runs, it is the
     * stack as it was at that script event, of which the native hook captures the top 64 entries (the deeper ones read
     * 0); otherwise it is read now, which only means something while a script runs. A copy: changing it doesn't change
     * the game.
     *
     * @return the stack, oldest first and the top at {@link #getIntStackSize()} - 1; empty if it can't be read or
     *         this build's mapping lacks it
     */
    public int[] getIntStack() {
        ScriptStack captured = scriptStack.get();
        return captured != null ? captured.ints().clone() : scripts.intStack();
    }

    /**
     * The script engine's int stack's size: at the script event whose handler is running, as {@link #getIntStack()}
     * says, else now.
     *
     * @return the size, or 0 if it can't be read
     */
    public int getIntStackSize() {
        ScriptStack captured = scriptStack.get();
        return captured != null ? captured.ints().length : scripts.intStackSize();
    }

    /**
     * The script engine's object stack, where strings travel: at the script event whose handler is running, of which
     * the native hook captures the top 16 entries (the deeper ones read null), else read now. A copy: changing it
     * doesn't change the game.
     *
     * @return the stack, oldest first: {@code String}s, {@code Integer}s and {@code Long}s, null for other kinds;
     *         empty if it can't be read or this build's mapping lacks it
     */
    public Object[] getObjectStack() {
        ScriptStack captured = scriptStack.get();
        return captured != null ? captured.objects().clone() : scripts.objectStack();
    }

    /**
     * The script engine's object stack's size: at the script event whose handler is running, else now.
     *
     * @return the size, or 0 if it can't be read
     */
    public int getObjectStackSize() {
        ScriptStack captured = scriptStack.get();
        return captured != null ? captured.objects().length : scripts.objectStackSize();
    }

    /**
     * Sets the stacks the stack getters give on the calling thread, for the handlers of one script event.
     * Internal: the event source sets it around posting a script event; plugins don't need it.
     *
     * @param stack the stacks the native hook captured, or null to read the game again
     */
    public void setScriptStack(ScriptStack stack) {
        if (stack == null) {
            scriptStack.remove();
        } else {
            scriptStack.set(stack);
        }
    }

    /**
     * Sets the components the script widget getters give on the calling thread, for the handlers of one script event.
     * Internal: the event source sets it around posting a script event; plugins don't need it.
     *
     * @param components the components the native hook captured, or null to read the game again
     */
    public void setScriptComponents(ScriptComponents components) {
        if (components == null) {
            scriptComponents.remove();
        } else {
            scriptComponents.set(components);
        }
    }

    /**
     * The script engine's active widget: the component its {@code cc_*} ops act on (operand 0), which a script points
     * at another with {@code cc_find} or {@code cc_create}. While a {@code ScriptPreFired} or {@code ScriptPostFired}
     * handler runs, it is the one at that script event, as the native hook captured it, read again now by its id;
     * otherwise it is the one now, which only means something while a script runs.
     *
     * @return a snapshot of the component; null if there is none, it has gone, or this build's mapping lacks it
     */
    public Widget getScriptActiveWidget() {
        ScriptComponents captured = scriptComponents.get();
        if (captured != null) {
            return captured.activeId() == -1 ? null : widgetReader.lookup(captured.activeId(), captured.activeIndex());
        }
        return widgetReader.at(scripts.activeComponent());
    }

    /**
     * The script engine's "dot" widget: the component its {@code .cc_*} ops act on (operand 1), captured or read as
     * {@link #getScriptActiveWidget()} is.
     *
     * @return a snapshot of the component; null if there is none, it has gone, or this build's mapping lacks it
     */
    public Widget getScriptDotWidget() {
        ScriptComponents captured = scriptComponents.get();
        if (captured != null) {
            return captured.dotId() == -1 ? null : widgetReader.lookup(captured.dotId(), captured.dotIndex());
        }
        return widgetReader.at(scripts.dotComponent());
    }

    /**
     * The writes the {@link Widget} setters make, waiting for the client thread to send them to the native hook.
     * Internal: used by the API's own packages; plugins write through {@link Widget}.
     *
     * @return the writes
     */
    public ComponentWrites getComponentWrites() {
        return widgetReader.getWrites();
    }

    /**
     * An inventory, as the client holds it now (a snapshot; see {@link ItemContainer}).
     *
     * @param inventoryId the inventory id, e.g. {@code InventoryID.INV}
     * @return the inventory, or null if the client doesn't hold it (the bank before it's been opened, say)
     */
    public ItemContainer getItemContainer(int inventoryId) {
        return inventories.inventory(inventoryId);
    }

    /**
     * Every inventory the client holds now (backpack, worn equipment, bank once opened, shops…).
     *
     * @return snapshots in no particular order; empty if the inventory table can't be read
     */
    public List<ItemContainer> getItemContainers() {
        return inventories.inventories();
    }

    /**
     * An item's definition, as on the world the client is on (members or free). Lua's {@code Engine.decodeObjType}.
     *
     * @param itemId the item id
     * @return the definition (the client's default type for an id without one), or null if config types can't be read
     */
    public ItemComposition getItemDefinition(int itemId) {
        return configTypes.obj(itemId, actorReader.membersWorld());
    }

    /**
     * An NPC type's definition. Lua's {@code Engine.decodeNpcType}.
     *
     * @param npcId the NPC type id (not a loaded NPC's index)
     * @return the definition (the client's default type for an id without one), or null if config types can't be read
     */
    public NPCComposition getNpcDefinition(int npcId) {
        return configTypes.npc(npcId);
    }

    /**
     * An object's definition. Lua's {@code Engine.decodeLocType}.
     *
     * @param objectId the object id
     * @return the definition (the client's default type for an id without one), or null if config types can't be read
     */
    public ObjectComposition getObjectDefinition(int objectId) {
        return configTypes.loc(objectId);
    }

    /**
     * A widget (interface component) by its packed id, as the client holds it now (a snapshot; see {@link Widget}).
     * The client loads a group's components the first time it is asked for one. The API only reads, so a group the
     * client hasn't loaded gives null. Lua's {@code IfType.retrieveIfType}.
     *
     * @param componentId {@code group << 16 | child}, e.g. from {@code gameval.InterfaceID}
     * @return the widget, or null if the client holds none there
     */
    public Widget getWidget(int componentId) {
        return getWidget(componentId >>> 16, componentId & 0xffff);
    }

    /**
     * A widget by group and child id.
     *
     * @param groupId the interface (group) id
     * @param childId the child's id within the group
     * @return the widget, or null if the client holds none there
     */
    public Widget getWidget(int groupId, int childId) {
        return widgetReader.getWidget(groupId, childId);
    }

    /**
     * Every static widget of an interface group, in one read of the group. Mirrors Lua {@code IfType.retrieveIfGroup}.
     * As {@link #getWidget(int)}, a group the client hasn't loaded gives null.
     *
     * @param groupId the interface (group) id
     * @return the group's widgets by child id, null where the client holds none; null if it holds none for the group
     */
    public Widget[] getWidgetGroup(int groupId) {
        return widgetReader.getGroup(groupId);
    }

    /**
     * The interface groups the client has open (on screen), from its open-interface map.
     *
     * @return the group ids, sorted; empty if they can't be read
     */
    public List<Integer> getOpenWidgetGroups() {
        return widgetReader.getOpenGroups();
    }

    /**
     * The interfaces opened inside components, read now: the client's sub-interface table, which says, for example,
     * which side panel the inventory is opened in, and how each was opened ({@link WidgetNode#getModalMode()}). The
     * top-level interface is opened in no component, so it isn't in it ({@link #getTopLevelInterfaceId()}).
     *
     * @return each interface by the packed id of the component it is opened in; empty if the table can't be read or
     *         this build's mapping lacks it
     */
    public Map<Integer, WidgetNode> getComponentTable() {
        return widgetReader.getComponentTable();
    }

    /**
     * The top-level interface: the group every other open interface hangs from (the fixed, resizable or side-panel
     * layout), opened in no component.
     *
     * @return its group id, e.g. {@code InterfaceID.TOPLEVEL_OSRS_STRETCH}; -1 while there is none (on the login
     *         screen) or if it can't be read
     */
    public int getTopLevelInterfaceId() {
        return widgetReader.getTopLevelInterface();
    }

    /**
     * The entries of the menu, read now (snapshots; see {@link MenuEntry}). While the menu is closed these are the
     * options for what the mouse is over, which the client rebuilds every cycle; while it is open, the open menu's.
     * The last entry is the top row, the one a left click runs, so the array is in reverse of the order on screen.
     *
     * @return the entries, bottom row ("Cancel") first; empty if the menu can't be read
     */
    public MenuEntry[] getMenuEntries() {
        List<MenuEntry> rows = new ArrayList<>(menuReader.rows());
        Collections.reverse(rows);
        return rows.toArray(new MenuEntry[0]);
    }

    /**
     * Whether the menu is open (right-clicked open, until it closes).
     *
     * @return true while it is open; false if it can't be read
     */
    public boolean isMenuOpen() {
        return menuReader.isOpen();
    }

    /**
     * Adds a row of the plugin's own to the menu, next to a row of the menu as it reads now, through the client's
     * native hook ({@link MenuEdits}). Set its option, target and callback on the entry returned; it is sent at the
     * end of the client thread's pass and shows from the client's next rebuild of the menu on (the next client cycle,
     * while the menu is closed), for as long as the row it sits next to shows. Clicking it runs the callback and
     * posts a {@code MenuOptionClicked} for it, and runs nothing in the client. Call this when the row appears
     * ({@code MenuEntryAdded}) rather than when the menu opens: an open menu isn't rebuilt, so the row would only
     * show on the menu after it.
     *
     * @param idx the index of the row to put it under, in {@link #getMenuEntries()}'s order (bottom first), or -1
     *            to put it at the top
     * @return the new row
     */
    public MenuEntry createMenuEntry(int idx) {
        return menuEdits.create(idx);
    }

    /**
     * Makes the menu show these rows and no others, through the client's native hook, as far as rules on the rows
     * can: a client row left out is hidden, and a client row moved to the top is swapped with the top row, each from
     * the next rebuild on and for as long as the row shows. Other orders aren't expressed, and rows of plugins' own
     * are managed by their own rules.
     *
     * @param entries the rows to show, bottom first, as {@link #getMenuEntries()} returns them
     */
    public void setMenuEntries(MenuEntry[] entries) {
        menuEdits.setMenuEntries(entries);
    }

    /**
     * Runs a menu action as a click on its entry would, through the client's native hook: the client's own row body
     * sends the op packet and starts the interaction. Same as
     * {@code getActions().menuAction(p0, p1, action, id, itemId)}; {@link Actions#click} sends the mouse click packet
     * first, as a real click does. The native entry carries no option or target, so those are not used.
     *
     * @param p0     the entry's first parameter (a loc's, ground item's or walk's scene x; a component's slot)
     * @param p1     the entry's second parameter (a loc's, ground item's or walk's scene y; a component's packed id)
     * @param action the action; see {@link Actions#isSupported}
     * @param id     the entry's identifier: an NPC's or player's index, a loc's or item's id, a component op (1-based)
     * @param itemId a component's item id, or -1 to read it from the widget
     * @param option unused
     * @param target unused
     */
    public void menuAction(int p0, int p1, MenuAction action, int id, int itemId, String option, String target) {
        actions.menuAction(p0, p1, action, id, itemId);
    }

    /**
     * The projectiles in flight, as one read of the client's list and of its keyed projectiles (snapshots; see
     * {@link Projectile}).
     *
     * @return the list's projectiles in list order, then the keyed ones; empty if none or if they can't be read
     */
    public Deque<Projectile> getProjectiles() {
        return new ArrayDeque<>(projectileReader.projectiles());
    }

    /**
     * Whether any projectile in flight is drawn. Mirrors Lua {@code ClientOp.areProjectilesRendered} (the docs say all
     * of them; the client's handler checks for any, and only in its list, not among the keyed projectiles).
     *
     * @return true if a projectile in the client's list isn't hidden
     */
    public boolean areProjectilesRendered() {
        return projectileReader.anyRendered();
    }

    /**
     * The graphics objects playing in the top-level world. Same as
     * {@code getTopLevelWorldView().getGraphicsObjects()}.
     *
     * @return the graphics objects in the world's list order; empty if none or if it can't be read
     */
    public Deque<GraphicsObject> getGraphicsObjects() {
        return topLevelWorldView.getGraphicsObjects();
    }

    /**
     * The camera's x, in local units of the top-level sceneReader. Lua's {@code Camera.getPos}.
     *
     * @return the camera's x, or -1 if it can't be read
     */
    public int getCameraX() {
        int[] eye = cameraTriple(cCameraPos);
        return eye == null ? -1 : eye[0];
    }

    /**
     * The camera's y (north), in local units. Lua's {@code Camera.getPos}'s {@code z}.
     *
     * @return the camera's y, or -1 if it can't be read
     */
    public int getCameraY() {
        int[] eye = cameraTriple(cCameraPos);
        return eye == null ? -1 : eye[2];
    }

    /**
     * The camera's height (more negative is higher), in local units. Lua's {@code Camera.getPos}'s {@code y}.
     *
     * @return the camera's height, or -1 if it can't be read
     */
    public int getCameraZ() {
        int[] eye = cameraTriple(cCameraPos);
        return eye == null ? -1 : eye[1];
    }

    /**
     * The x of the point the camera looks at, in local units. Lua's {@code Camera.getLookAt}.
     *
     * @return the focal point's x, or -1 if it can't be read or the mapping doesn't have it
     */
    public int getCameraFocalPointX() {
        int[] at = cCameraLookAt < 0 ? null : cameraTriple(cCameraLookAt);
        return at == null ? -1 : at[0];
    }

    /**
     * The y (north) of the point the camera looks at, in local units. Lua's {@code Camera.getLookAt}'s {@code z}.
     *
     * @return the focal point's y, or -1 if it can't be read or the mapping doesn't have it
     */
    public int getCameraFocalPointY() {
        int[] at = cCameraLookAt < 0 ? null : cameraTriple(cCameraLookAt);
        return at == null ? -1 : at[2];
    }

    /**
     * The height of the point the camera looks at (more negative is higher). Lua's {@code Camera.getLookAt}'s
     * {@code y}.
     *
     * @return the focal point's height, or -1 if it can't be read or the mapping doesn't have it
     */
    public int getCameraFocalPointZ() {
        int[] at = cCameraLookAt < 0 ? null : cameraTriple(cCameraLookAt);
        return at == null ? -1 : at[1];
    }

    /**
     * The camera's pitch, in JAU14 (16384 per turn): the client's own value. Lua's {@code Camera.getLookAtAngles} gives
     * it in degrees ({@code pitch × 360 / 16384}).
     *
     * @return the pitch, or -1 if it can't be read
     */
    public int getCameraPitch() {
        long client = clientBase();
        return client == 0 ? -1 : safeInt(client + cCameraAngles, -1);
    }

    /**
     * The camera's yaw, in JAU14 (16384 per turn): the client's own value. Lua's {@code Camera.getLookAtAngles} gives
     * it in degrees.
     *
     * @return the yaw, or -1 if it can't be read
     */
    public int getCameraYaw() {
        long client = clientBase();
        return client == 0 ? -1 : safeInt(client + cCameraAngles + 4L, -1);
    }

    /**
     * The scale of the world, the zoom value projections multiply by: the viewport height times the zoom divided by
     * 334, worked out each frame.
     *
     * @return the scale, or -1 if it can't be read
     */
    public int getScale() {
        long client = clientBase();
        return client == 0 || cScale < 0 ? -1 : safeInt(client + cScale, -1);
    }

    /**
     * The zoom setting the client derives {@link #getScale()} from.
     *
     * @return the zoom, or -1 if it can't be read or the mapping doesn't have it
     */
    public int getCameraZoom() {
        long client = clientBase();
        return client == 0 || cZoom < 0 ? -1 : safeInt(client + cZoom, -1);
    }

    /**
     * Whether a Lua plugin has taken control of the camera ({@code setControl(true)}). Mirrors Lua
     * {@code Camera.isControlled}.
     *
     * @return true if the camera is under plugin control; false otherwise or if it can't be read
     */
    public boolean isCameraControlled() {
        return cameraControlledRva >= 0 && safeU8(memory.moduleBase() + cameraControlledRva, 0) != 0;
    }

    /**
     * Whether a Lua plugin has pointed the camera at an NPC or player ({@code targetNpc}/{@code targetPlayer}). Mirrors
     * Lua {@code Camera.hasTarget}.
     *
     * @return true if a camera target is set; false otherwise or if it can't be read
     */
    public boolean hasCameraTarget() {
        return cameraTargetRva >= 0 && safeInt(memory.moduleBase() + cameraTargetRva, -1) != -1;
    }

    /**
     * The game canvas's width in the client's own (DPI-scaled) pixels, the units {@link Perspective} returns. Lua's
     * {@code Graphics.clientDimensions}.
     *
     * @return the width, or -1 if it can't be read
     */
    public int getCanvasWidth() {
        long renderer = renderer();
        return renderer == 0 ? -1 : Math.max(-1, safeInt(renderer + rCanvasWidth, -1));
    }

    /**
     * The game canvas's height in the client's own pixels.
     *
     * @return the height, or -1 if it can't be read
     */
    public int getCanvasHeight() {
        long renderer = renderer();
        return renderer == 0 ? -1 : Math.max(-1, safeInt(renderer + rCanvasHeight, -1));
    }

    /**
     * The game window's width in physical pixels: with display scaling above 100% it is larger than
     * {@link #getCanvasWidth()}. Mirrors Lua {@code osrs.screenSize}.
     *
     * @return the width, or -1 if it can't be read or the mapping doesn't have it
     */
    public int getScreenWidth() {
        long renderer = renderer();
        return renderer == 0 || rScreenWidth < 0 ? -1 : Math.max(-1, safeInt(renderer + rScreenWidth, -1));
    }

    /**
     * The game window's height in physical pixels. Mirrors Lua {@code osrs.screenSize}.
     *
     * @return the height, or -1 if it can't be read or the mapping doesn't have it
     */
    public int getScreenHeight() {
        long renderer = renderer();
        return renderer == 0 || rScreenHeight < 0 ? -1 : Math.max(-1, safeInt(renderer + rScreenHeight, -1));
    }

    /**
     * The camera as it is now, for projecting points of the top-level scene onto the canvas, which {@link Perspective}
     * is built on. Reads what Lua's {@code Graphics.worldToScreenCoord} reads, once.
     *
     * @return the projection, or null if the camera or the canvas can't be read, or the mapping lacks a projection key
     */
    public CameraProjection getCameraProjection() {
        if (!canProject) {
            return null;
        }
        long client = clientBase();
        long renderer = renderer();
        long world = client == 0 ? 0 : safePointer(client + cTopWorld);
        long sc = world == 0 ? 0 : safePointer(world + wScene);
        if (renderer == 0 || sc == 0) {
            return null;
        }
        int[] eye = readInts(client + cCameraPos, 3);
        ByteBuffer trig = readBlock(sc + scTrig, 16);
        if (eye == null || trig == null) {
            return null;
        }
        long viewport = renderer + rViewport;
        int scale = safeInt(client + cScale, 0);
        int centerX = safeInt(viewport + vCenterX, 0) + safeInt(viewport + vOffsetX, 0);
        int centerY = safeInt(viewport + vCenterY, 0) + safeInt(viewport + vOffsetY, 0);
        int canvasWidth = safeInt(renderer + rCanvasWidth, 0);
        int canvasHeight = safeInt(renderer + rCanvasHeight, 0);
        int screenWidth = safeInt(renderer + rScreenWidth, 0);
        int screenHeight = safeInt(renderer + rScreenHeight, 0);
        if (screenWidth <= 0 || screenHeight <= 0) {
            return null;
        }
        return new CameraProjection(eye[0], eye[1], eye[2], trig.getFloat(0), trig.getFloat(4), trig.getFloat(8),
                trig.getFloat(12), scale, centerX, centerY, (float) canvasWidth / (float) screenWidth,
                (float) canvasHeight / (float) screenHeight);
    }

    /**
     * The handle on the NPC at an index now: the one handed out before if it's still the same NPC, else a new one.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param index the NPC's index
     * @return its handle, or null if no NPC is at that index in the top-level world
     */
    public NPC npc(int index) {
        long address = actorReader.npc(index);
        if (address == 0) {
            return null;
        }
        int cycle = actorReader.npcCreationCycle(address);
        return npcs.compute(index, (i, cached) -> cached != null && cached.isPinnedTo(address, cycle)
                ? cached : new NPC(this, index, address, cycle));
    }

    /**
     * The handle on the player at an index now: the one handed out before if it's still the same player, else a new
     * one.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param index the player's index
     * @return its handle, or null if no player is at that index in the top-level world
     */
    public Player player(int index) {
        long address = actorReader.player(index);
        if (address == 0) {
            return null;
        }
        String name = actorReader.playerName(address);
        return players.compute(index, (i, cached) -> cached != null && cached.isPinnedTo(address, name)
                ? cached : new Player(this, index, address, name));
    }

    /**
     * The handle on an actor a reference names, such as an interacting target or a projectile's endpoint.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param type  {@link Actor#INTERACTING_NPC} or {@link Actor#INTERACTING_PLAYER}; anything else names no actor
     * @param index the NPC or player index
     * @return the handle, or null for no actor, a world entity, or an actor not loaded in the top-level world
     */
    public Actor actor(int type, int index) {
        if (index < 0) {
            return null;
        }
        return switch (type) {
            case Actor.INTERACTING_NPC -> npc(index);
            case Actor.INTERACTING_PLAYER -> player(index);
            default -> null;
        };
    }

    /**
     * The indices of every NPC in the top-level world's active list (Lua's {@code ClientOp.getNpcIdAll}).
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @return the NPC indices, empty when logged out
     */
    public int[] npcIndices() {
        long world = actorReader.topWorld();
        if (world == 0) {
            return new int[0];
        }
        int count = safeInt(world + wNpcCount, 0);
        return indexArray(safePointer(world + wNpcIndices), count, MAX_NPCS);
    }

    /**
     * The indices of every loaded player: the inline index array in the client (Lua's
     * {@code ClientOp.getPlayerIdAll}).
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @return the player indices, empty when logged out
     */
    public int[] playerIndices() {
        long client = clientBase();
        if (client == 0) {
            return new int[0];
        }
        int count = safeInt(client + cPlayerCount, 0);
        return indexArray(client + cPlayerIndices, count, MAX_PLAYERS);
    }

    /**
     * Every NPC in the top-level world's active list.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @return their handles, in list order
     */
    public List<NPC> npcList() {
        int[] indices = npcIndices();
        List<NPC> out = new ArrayList<>(indices.length);
        for (int index : indices) {
            NPC npc = npc(index);
            if (npc != null) {
                out.add(npc);
            }
        }
        return out;
    }

    /**
     * Every loaded player.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @return their handles, in the client's list order
     */
    public List<Player> playerList() {
        int[] indices = playerIndices();
        List<Player> out = new ArrayList<>(indices.length);
        for (int index : indices) {
            Player player = player(index);
            if (player != null) {
                out.add(player);
            }
        }
        return out;
    }

    /**
     * The loaded map's base: the world tile at scene tile (0, 0).
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @return the base as a world coordinate on level 0, or null if it can't be read
     */
    public MapCoord mapBase() {
        int[] base = sceneReader.mapBase();
        return base == null ? null : new MapCoord(0, base[0], base[1]);
    }

    /**
     * Turns a scene tile into a world tile by adding the map base.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param tile a scene tile, as the readers return it
     * @return the world tile, or null if the map base can't be read
     */
    public WorldPoint toWorld(MapCoord tile) {
        int[] base = sceneReader.mapBase();
        return base == null ? null : new WorldPoint(tile.x() + base[0], tile.z() + base[1], tile.level());
    }

    /**
     * The client's raw game state ({@code Client.gameState}). Values seen in the client: 10 title screen, 20 logging
     * in, 25 loading, 30 logged in, 40 connection lost, 45 hopping worlds.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @return the game state, or -1 if it can't be read
     */
    public int rawGameState() {
        long client = clientBase();
        return client == 0 ? -1 : safeInt(client + cGameState, -1);
    }

    /**
     * Every stat slot's XP, effective level and base level, in three reads.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @return the three arrays, or null before the server sends stats or if they can't be read
     */
    public Stats stats() {
        long client = clientBase();
        if (client == 0 || safeInt(client + cStatsValid, 0) <= 0) {
            return null;
        }
        int[] xp = readInts(client + cSkillXp, statCount);
        int[] level = readInts(client + cSkillLevel, statCount);
        int[] base = readInts(client + cSkillBase, statCount);
        return xp == null || level == null || base == null ? null : new Stats(xp, level, base);
    }

    /**
     * Every stat at once, indexed by stat id. Arrays are owned by the caller.
     *
     * @param xp    XP per stat
     * @param level effective (current) level per stat
     * @param base  base level per stat
     */
    public record Stats(int[] xp, int[] level, int[] base) {
    }

    private int stat(int arrayOffset, int statId) {
        long client = clientBase();
        if (client == 0 || statId < 0 || statId >= statCount || safeInt(client + cStatsValid, 0) <= 0) {
            return -1;
        }
        return safeInt(client + arrayOffset + statId * 4L, -1);
    }

    /**
     * What the skill arrays hold before the server sends the stats.
     *
     * @return one -1 per stat slot the client keeps
     */
    private int[] missingStats() {
        int[] values = new int[statCount];
        Arrays.fill(values, -1);
        return values;
    }

    /**
     * The raw world-type bitmask (bit 0 set on members worlds).
     *
     * @return the world flags, or 0 if they can't be read
     */
    private int worldFlags() {
        long client = clientBase();
        return client == 0 || cWorldFlags < 0 ? 0 : safeInt(client + cWorldFlags, 0);
    }

    /**
     * How many varps the client has.
     *
     * @return the varp count (ids 0 to this minus 1), or 0 if unknown
     */
    private int varpCount() {
        if (varpCountRva < 0) {
            return 0;
        }
        int count = safeInt(memory.moduleBase() + varpCountRva, 0);
        return count > 0 && count <= MAX_VARPS ? count : 0;
    }

    /**
     * The varc store's sorted entry array.
     *
     * @return {begin address, entry count}, or null if the mapping lacks the varc keys or the store can't be read
     */
    private long[] varcEntries() {
        long client = varcsMapped() ? clientBase() : 0;
        long store = client == 0 ? 0 : safePointer(client + cVarcs);
        if (store == 0) {
            return null;
        }
        long begin = safePointer(store + varcsBegin);
        long end = safePointer(store + varcsEnd);
        long count = (end - begin) / varcStride;
        if (begin == 0 || count <= 0 || count > MAX_VARCS || (end - begin) % varcStride != 0) {
            return null;
        }
        return new long[]{begin, count};
    }

    /**
     * Whether the mapping has every key the varc reads need.
     *
     * @return true if the varc store can be read
     */
    private boolean varcsMapped() {
        return cVarcs >= 0 && varcsBegin >= 0 && varcsEnd >= 0 && varcStride > 0 && varcKey >= 0 && varcValue >= 0
                && varcType >= 0;
    }

    /**
     * Binary search over the sorted varc entries, as the client's own lookup does.
     *
     * @param id the varc id
     * @return the entry's address, or 0 if the varc isn't set
     */
    private long findVarc(int id) {
        long[] entries = varcEntries();
        if (entries == null) {
            return 0;
        }
        long lo = 0;
        long hi = entries[1];
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            int key = safeInt(entries[0] + mid * varcStride + varcKey, Integer.MAX_VALUE);
            if (key < id) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo >= entries[1]) {
            return 0;
        }
        long entry = entries[0] + lo * varcStride;
        return safeInt(entry + varcKey, Integer.MIN_VALUE) == id ? entry : 0;
    }

    /**
     * Three consecutive ints of the client struct (a camera position).
     *
     * @param offset the first int's offset
     * @return the ints, or null if they can't be read
     */
    private int[] cameraTriple(int offset) {
        long client = clientBase();
        return client == 0 ? null : readInts(client + offset, 3);
    }

    private long renderer() {
        long client = clientBase();
        return client == 0 ? 0 : safePointer(client + cRenderer);
    }

    private int[] indexArray(long base, int count, int limit) {
        if (base == 0 || count <= 0 || count > limit) {
            return new int[0];
        }
        int[] indices = readInts(base, count);
        return indices == null ? new int[0] : indices;
    }
}
