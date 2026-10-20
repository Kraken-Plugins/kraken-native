package com.kraken.api.impl;

import com.kraken.api.Actor;
import com.kraken.api.CameraProjection;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.ItemComposition;
import com.kraken.api.ItemContainer;
import com.kraken.api.NPC;
import com.kraken.api.NPCComposition;
import com.kraken.api.ObjectComposition;
import com.kraken.api.Player;
import com.kraken.api.Point;
import com.kraken.api.ProfileType;
import com.kraken.api.Projectile;
import com.kraken.api.Scene;
import com.kraken.api.Skill;
import com.kraken.api.WorldType;
import com.kraken.api.WorldView;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.ClientState;
import com.kraken.api.lua.Entity;
import com.kraken.api.lua.FineCoord;
import com.kraken.api.lua.Graphics;
import com.kraken.api.lua.Inventory;
import com.kraken.api.lua.LuaApi;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Npc;
import com.kraken.api.lua.Vector2;
import com.kraken.api.lua.types.LocType;
import com.kraken.api.lua.types.NpcType;
import com.kraken.api.lua.types.ObjType;
import com.kraken.api.widgets.Widget;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Client} over the Lua mirror: {@code ClientOp} for actors, inventories, definitions and projectiles,
 * {@code ClientState} for the game state, skills and vars, {@code InterfaceQuery} for widgets, {@code Camera} for the
 * camera, and the Java {@code Scene} for the map base the world tiles need and the scene's objects.
 *
 * <p>It keeps one handle per actor index ({@link #npc}, {@link #player}), so asking twice for the same actor gives the
 * same object, and a handle's identity holds as long as the actor does. A handle is replaced when another actor takes
 * its index, not when a read finds the index empty, so one failed read doesn't cost a handle its identity.
 */
final class ClientImpl implements Client {

    private final LuaApi lua;
    private final ClientOp op;
    private final ClientState state;
    private final TopLevelWorldView topLevel;
    private final Map<Integer, NpcImpl> npcs = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerImpl> players = new ConcurrentHashMap<>();

    ClientImpl(LuaApi lua) {
        this.lua = lua;
        this.op = lua.clientOp();
        this.state = lua.clientState();
        this.topLevel = new TopLevelWorldView(this, lua);
    }

    @Override
    public GameState getGameState() {
        return GameState.of(state.getGameState());
    }

    @Override
    public int getTickCount() {
        return state.getTickCount();
    }

    @Override
    public int getGameCycle() {
        return state.getCycle();
    }

    @Override
    public EnumSet<WorldType> getWorldType() {
        return WorldType.fromMask(state.getWorldFlags());
    }

    @Override
    public boolean isLoggedIn() {
        return state.isLoggedIn();
    }

    @Override
    public ProfileType getProfileType() {
        return ProfileType.valueOf(state.getProfileType().name());
    }

    @Override
    public Player getLocalPlayer() {
        int index = op.getLocalPlayerIndex();
        return index < 0 ? null : player(index);
    }

    @Override
    public NPC getFollower() {
        for (NPC npc : npcList()) {
            NpcImpl impl = (NpcImpl) npc;
            if (impl.isFollower() && impl.isLocalFollower()) {
                return impl;
            }
        }
        return null;
    }

    @Override
    public WorldView getTopLevelWorldView() {
        return topLevel;
    }

    @Override
    public WorldView getWorldView(int id) {
        return id == WorldView.TOPLEVEL ? topLevel : null;
    }

    @Override
    public List<NPC> getNpcs() {
        return npcList();
    }

    @Override
    public List<Player> getPlayers() {
        return playerList();
    }

    @Override
    public Scene getScene() {
        return topLevel.getScene();
    }

    @Override
    public int getRealSkillLevel(Skill skill) {
        return state.getStatBaseLevel(skill.ordinal());
    }

    @Override
    public int getBoostedSkillLevel(Skill skill) {
        return state.getStatEffectiveLevel(skill.ordinal());
    }

    @Override
    public int getSkillExperience(Skill skill) {
        return state.getStatXP(skill.ordinal());
    }

    @Override
    public int[] getRealSkillLevels() {
        ClientState.StatArrays stats = state.getStatsAll();
        return stats == null ? missingStats() : stats.base();
    }

    @Override
    public int[] getBoostedSkillLevels() {
        ClientState.StatArrays stats = state.getStatsAll();
        return stats == null ? missingStats() : stats.level();
    }

    @Override
    public int[] getSkillExperiences() {
        ClientState.StatArrays stats = state.getStatsAll();
        return stats == null ? missingStats() : stats.xp();
    }

    @Override
    public int getTotalLevel() {
        ClientState.StatArrays stats = state.getStatsAll();
        if (stats == null) {
            return -1;
        }
        int total = 0;
        for (int skill = 0; skill < Skill.values().length && skill < stats.base().length; skill++) {
            total += stats.base()[skill];
        }
        return total;
    }

    @Override
    public int getVarbitValue(int varbitId) {
        return state.getVarbit(varbitId);
    }

    @Override
    public int getVarpValue(int varpId) {
        if (varpId < 0 || varpId >= state.getVarpCount()) {
            return -1;
        }
        return op.vars().varp(varpId);
    }

    @Override
    public int getServerVarpValue(int varpId) {
        return state.getVarp(varpId);
    }

    @Override
    public int[] getVarps() {
        return state.getVarbitVarps();
    }

    @Override
    public int[] getServerVarps() {
        return state.getVarps();
    }

    /**
     * Reads every integer varc to tell an unset varc from one holding -1 (Lua's {@code getIntegerVarc} gives -1 for
     * both), so an unset varc reads 0 as in RuneLite.
     *
     * @param varcId the varc id
     * @return the varc's value, or 0 if it isn't set
     */
    @Override
    public int getVarcIntValue(int varcId) {
        return state.getIntegerVarcs().getOrDefault(varcId, 0);
    }

    @Override
    public String getVarcStrValue(int varcId) {
        return state.getStringVarc(varcId);
    }

    @Override
    public ItemContainer getItemContainer(int inventoryId) {
        Inventory inventory = op.getInventory(inventoryId);
        return inventory == null ? null : ItemContainerImpl.of(inventory, op.invSize(inventoryId));
    }

    @Override
    public ItemComposition getItemDefinition(int itemId) {
        ObjType type = lua.engine().decodeObjType(itemId);
        return type == null ? null : new ItemCompositionImpl(type, op.vars());
    }

    @Override
    public NPCComposition getNpcDefinition(int npcId) {
        NpcType type = lua.engine().decodeNpcType(npcId);
        return type == null ? null : new NpcCompositionImpl(type, op.vars());
    }

    @Override
    public ObjectComposition getObjectDefinition(int objectId) {
        LocType type = lua.engine().decodeLocType(objectId);
        return type == null ? null : new ObjectCompositionImpl(type, op.vars());
    }

    @Override
    public Widget getWidget(int componentId) {
        return getWidget(componentId >>> 16, componentId & 0xffff);
    }

    @Override
    public Widget getWidget(int groupId, int childId) {
        return WidgetImpl.of(lua.interfaces(), lua.interfaces().retrieveIfType(groupId, childId));
    }

    @Override
    public List<Integer> getOpenWidgetGroups() {
        return lua.interfaces().getOpenGroups();
    }

    @Override
    public Deque<Projectile> getProjectiles() {
        Deque<Projectile> out = new ArrayDeque<>();
        for (com.kraken.api.lua.Projectile projectile : op.getProjectiles()) {
            out.add(new ProjectileImpl(this, projectile));
        }
        return out;
    }

    @Override
    public boolean areProjectilesRendered() {
        return op.areProjectilesRendered();
    }

    @Override
    public int getCameraX() {
        FineCoord eye = lua.camera().getPos();
        return eye == null ? -1 : eye.x();
    }

    /**
     * The camera's north coordinate, which Lua keeps in {@code z}.
     *
     * @return the camera's y, or -1 if it can't be read
     */
    @Override
    public int getCameraY() {
        FineCoord eye = lua.camera().getPos();
        return eye == null ? -1 : eye.z();
    }

    /**
     * The camera's height, which Lua keeps in {@code y}.
     *
     * @return the camera's height, or -1 if it can't be read
     */
    @Override
    public int getCameraZ() {
        FineCoord eye = lua.camera().getPos();
        return eye == null ? -1 : eye.y();
    }

    @Override
    public int getCameraPitch() {
        return lua.camera().getPitch();
    }

    @Override
    public int getCameraYaw() {
        return lua.camera().getYaw();
    }

    @Override
    public int getScale() {
        return lua.camera().getScale();
    }

    @Override
    public int getCanvasWidth() {
        Vector2 size = lua.graphics().clientDimensions();
        return size == null ? -1 : (int) size.x();
    }

    @Override
    public int getCanvasHeight() {
        Vector2 size = lua.graphics().clientDimensions();
        return size == null ? -1 : (int) size.y();
    }

    /**
     * Lua's projection with RuneLite's axes: Lua takes the height second and north third.
     *
     * @return the projection, or null if it can't be read
     */
    @Override
    public CameraProjection getCameraProjection() {
        Graphics.Projection projection = lua.graphics().projection();
        if (projection == null) {
            return null;
        }
        return (x, y, z) -> {
            Vector2 canvas = projection.project(x, z, y);
            return canvas == null ? null : new Point((int) canvas.x(), (int) canvas.y());
        };
    }

    /**
     * The handle on the NPC at an index now: the one handed out before if it's still the same NPC, else a new one.
     *
     * @param index the NPC's index
     * @return its handle, or null if no NPC is at that index in the top-level world
     */
    NpcImpl npc(int index) {
        Npc pinned = op.pinNpc(index);
        if (pinned == null) {
            return null;
        }
        return npcs.compute(index, (i, cached) -> cached != null && cached.pin().equals(pinned)
                ? cached : new NpcImpl(this, pinned));
    }

    /**
     * The handle on the player at an index now: the one handed out before if it's still the same player, else a new
     * one.
     *
     * @param index the player's index
     * @return its handle, or null if no player is at that index in the top-level world
     */
    PlayerImpl player(int index) {
        com.kraken.api.lua.Player pinned = op.pinPlayer(index);
        if (pinned == null) {
            return null;
        }
        return players.compute(index, (i, cached) -> cached != null && cached.pin().equals(pinned)
                ? cached : new PlayerImpl(this, pinned));
    }

    /**
     * The handle on an actor a Lua reference names, such as an interacting target or a projectile's endpoint.
     *
     * @param type  {@link Entity#TARGET_NPC} or {@link Entity#TARGET_PLAYER}; anything else names no actor
     * @param index the NPC or player index
     * @return the handle, or null for no actor, a world entity, or an actor not loaded in the top-level world
     */
    Actor actor(int type, int index) {
        if (index < 0) {
            return null;
        }
        return switch (type) {
            case Entity.TARGET_NPC -> npc(index);
            case Entity.TARGET_PLAYER -> player(index);
            default -> null;
        };
    }

    /**
     * The Lua mirror this client reads through, for the adapters that need more than the client's own methods.
     *
     * @return the Lua mirror
     */
    LuaApi lua() {
        return lua;
    }

    /**
     * Every NPC in the top-level world's active list.
     *
     * @return their handles, in list order
     */
    List<NPC> npcList() {
        int[] indices = op.getNpcIdAll();
        List<NPC> out = new ArrayList<>(indices.length);
        for (int index : indices) {
            NpcImpl npc = npc(index);
            if (npc != null) {
                out.add(npc);
            }
        }
        return out;
    }

    /**
     * Every loaded player.
     *
     * @return their handles, in the client's list order
     */
    List<Player> playerList() {
        int[] indices = op.getPlayerIdAll();
        List<Player> out = new ArrayList<>(indices.length);
        for (int index : indices) {
            PlayerImpl player = player(index);
            if (player != null) {
                out.add(player);
            }
        }
        return out;
    }

    /**
     * Turns a scene tile into a world tile by adding the map base.
     *
     * @param scene a scene tile, as the Lua mirror returns it
     * @return the world tile, or null if the map base can't be read
     */
    WorldPoint toWorld(MapCoord scene) {
        MapCoord world = lua.scene().toWorld(scene);
        return world == null ? null : new WorldPoint(world.x(), world.z(), world.level());
    }

    /**
     * What the skill arrays hold before the server sends the stats.
     *
     * @return one -1 per stat slot the client keeps
     */
    private int[] missingStats() {
        int[] values = new int[state.getStatCount()];
        Arrays.fill(values, -1);
        return values;
    }
}
