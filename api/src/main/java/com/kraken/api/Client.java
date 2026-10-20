package com.kraken.api;

import com.kraken.api.widgets.Widget;

import java.util.Deque;
import java.util.EnumSet;
import java.util.List;

/**
 * The game client, as RuneLite's {@code Client}: the root of the RuneLite-shaped API. Get it from
 * {@code KrakenApi.client()}.
 *
 * <p>Every method is backed by a read the Lua mirror ({@code KrakenApi.lua()}) already does. RuneLite methods Kraken
 * can't back (menus, input, models, setters) aren't declared. Reads are tolerant: an unreadable or logged-out state
 * gives -1, false, "", null or an empty list rather than an exception.
 *
 * <p>Read on any thread. Each call is one or more reads of the game process, so code that needs a value several times
 * in a row should keep it.
 */
public interface Client {

    /**
     * The client's game state.
     *
     * @return the state; {@link GameState#UNKNOWN} if it can't be read
     */
    GameState getGameState();

    /**
     * Server ticks processed since login.
     *
     * @return the tick count, or 0 if it can't be read
     */
    int getTickCount();

    /**
     * The client's cycle counter: one per 20 ms client cycle, the clock spawn cycles are measured on.
     *
     * @return the cycle, or -1 if it can't be read
     */
    int getGameCycle();

    /**
     * The kinds of world the client is on, from the world flags.
     *
     * @return the world's types; empty if the flags can't be read
     */
    EnumSet<WorldType> getWorldType();

    /**
     * Whether the client has a login session and is in the world. Kraken extra, mirrors Lua
     * {@code ClientState.isLoggedIn}.
     *
     * @return true when logged in
     */
    boolean isLoggedIn();

    /**
     * The kind of world, as the client decodes its world flags for Lua. Kraken extra, mirrors Lua
     * {@code ClientState.getProfileType}.
     *
     * @return the profile type; {@link ProfileType#STANDARD} if the flags can't be read
     */
    ProfileType getProfileType();

    /**
     * The local player.
     *
     * @return its handle, or null when logged out
     */
    Player getLocalPlayer();

    /**
     * The local player's follower, such as a pet.
     *
     * @return the follower's handle, or null if the local player has none loaded
     */
    NPC getFollower();

    /**
     * The top-level world: the main map, its actors and its scene.
     *
     * @return the top-level world view
     */
    WorldView getTopLevelWorldView();

    /**
     * A world view by id.
     *
     * @param id the view's id; {@link WorldView#TOPLEVEL} for the top-level world
     * @return the view, or null for an id that isn't loaded (sub-worlds aren't wrapped yet)
     */
    WorldView getWorldView(int id);

    /**
     * The NPCs loaded in the top-level world. Same as {@code getTopLevelWorldView().npcs()}.
     *
     * @return their handles; the same handle objects from call to call while each NPC stays loaded
     */
    List<NPC> getNpcs();

    /**
     * The players loaded. Same as {@code getTopLevelWorldView().players()}.
     *
     * @return their handles; the same handle objects from call to call while each player stays loaded
     */
    List<Player> getPlayers();

    /**
     * The top-level world's scene. Same as {@code getTopLevelWorldView().getScene()}.
     *
     * @return the scene
     */
    Scene getScene();

    /**
     * A skill's level from its XP.
     *
     * @param skill the skill
     * @return the base level, or -1 before the server sends the stats
     */
    int getRealSkillLevel(Skill skill);

    /**
     * A skill's current level, boosted or drained.
     *
     * @param skill the skill
     * @return the current level, or -1 before the server sends the stats
     */
    int getBoostedSkillLevel(Skill skill);

    /**
     * A skill's XP.
     *
     * @param skill the skill
     * @return the XP, or -1 before the server sends the stats
     */
    int getSkillExperience(Skill skill);

    /**
     * Every skill's level from its XP, in one read.
     *
     * @return the base levels indexed by {@link Skill#ordinal()}; all -1 before the server sends the stats
     */
    int[] getRealSkillLevels();

    /**
     * Every skill's current level, in one read.
     *
     * @return the current levels indexed by {@link Skill#ordinal()}; all -1 before the server sends the stats
     */
    int[] getBoostedSkillLevels();

    /**
     * Every skill's XP, in one read.
     *
     * @return the XP indexed by {@link Skill#ordinal()}; all -1 before the server sends the stats
     */
    int[] getSkillExperiences();

    /**
     * The total level: the sum of every skill's level from its XP.
     *
     * @return the total, or -1 before the server sends the stats
     */
    int getTotalLevel();

    /**
     * A varbit's value.
     *
     * @param varbitId the varbit id
     * @return its value, or -1 if its definition or varp can't be read
     */
    int getVarbitValue(int varbitId);

    /**
     * A varp's value as the client holds it, including any change the client has made locally (the array varbits are
     * read from), as RuneLite's.
     *
     * @param varpId the varp id
     * @return its value, or -1 for an id outside the varp array or if it can't be read
     */
    int getVarpValue(int varpId);

    /**
     * A varp's value as the server last sent it (Lua's {@code getVarp}).
     *
     * @param varpId the varp id
     * @return its value, or -1 for an id outside the varp array or if it can't be read
     */
    int getServerVarpValue(int varpId);

    /**
     * Every varp as the client holds it (see {@link #getVarpValue}).
     *
     * @return the values indexed by varp id; empty if they can't be read
     */
    int[] getVarps();

    /**
     * Every varp as the server last sent it.
     *
     * @return the values indexed by varp id; empty if they can't be read
     */
    int[] getServerVarps();

    /**
     * An integer varc's value.
     *
     * @param varcId the varc id
     * @return its value, or 0 if it isn't set (as RuneLite)
     */
    int getVarcIntValue(int varcId);

    /**
     * A string varc's value.
     *
     * @param varcId the varc id
     * @return its value, or "" if it isn't set
     */
    String getVarcStrValue(int varcId);

    /**
     * An inventory, as the client holds it now (a snapshot; see {@link ItemContainer}).
     *
     * @param inventoryId the inventory id, e.g. {@code InventoryID.INV}
     * @return the inventory, or null if the client doesn't hold it (the bank before it's been opened, say)
     */
    ItemContainer getItemContainer(int inventoryId);

    /**
     * An item's definition, as on the world the client is on (members or free).
     *
     * @param itemId the item id
     * @return the definition (the client's default type for an id without one), or null if config types can't be read
     */
    ItemComposition getItemDefinition(int itemId);

    /**
     * An NPC type's definition.
     *
     * @param npcId the NPC type id (not a loaded NPC's index)
     * @return the definition (the client's default type for an id without one), or null if config types can't be read
     */
    NPCComposition getNpcDefinition(int npcId);

    /**
     * An object's definition.
     *
     * @param objectId the object id
     * @return the definition (the client's default type for an id without one), or null if config types can't be read
     */
    ObjectComposition getObjectDefinition(int objectId);

    /**
     * A widget (interface component) by its packed id, as the client holds it now (a snapshot; see {@link Widget}).
     * The client loads a group's components the first time it is asked for one; Kraken can't ask, so a group the
     * client hasn't loaded gives null. Mirrors Lua {@code IfType.retrieveIfType}.
     *
     * @param componentId {@code group << 16 | child}, e.g. from {@code gameval.InterfaceID}
     * @return the widget, or null if the client holds none there
     */
    Widget getWidget(int componentId);

    /**
     * A widget by group and child id.
     *
     * @param groupId the interface (group) id
     * @param childId the child's id within the group
     * @return the widget, or null if the client holds none there
     */
    Widget getWidget(int groupId, int childId);

    /**
     * The interface groups the client has open (on screen). Kraken extra, from the client's open-interface map.
     *
     * @return the group ids, sorted; empty if they can't be read
     */
    List<Integer> getOpenWidgetGroups();

    /**
     * The projectiles in flight, as one read of the client's list (snapshots; see {@link Projectile}).
     *
     * @return the projectiles in the client's list order, as RuneLite's deque; empty if none or if it can't be read
     */
    Deque<Projectile> getProjectiles();

    /**
     * Whether any projectile in flight is drawn. Kraken extra, mirrors Lua {@code ClientOp.areProjectilesRendered}
     * (the docs say all of them; the client's handler checks for any).
     *
     * @return true if a projectile in flight isn't hidden
     */
    boolean areProjectilesRendered();

    /**
     * The camera's x, in local units of the top-level scene. Mirrors Lua {@code Camera.getPos}.
     *
     * @return the camera's x, or -1 if it can't be read
     */
    int getCameraX();

    /**
     * The camera's y (north), in local units. Mirrors Lua {@code Camera.getPos}'s {@code z}.
     *
     * @return the camera's y, or -1 if it can't be read
     */
    int getCameraY();

    /**
     * The camera's height (more negative is higher), in local units. Mirrors Lua {@code Camera.getPos}'s {@code y}.
     *
     * @return the camera's height, or -1 if it can't be read
     */
    int getCameraZ();

    /**
     * The camera's pitch, in JAU14 (16384 per turn), as RuneLite's: the client's own value.
     *
     * @return the pitch, or -1 if it can't be read
     */
    int getCameraPitch();

    /**
     * The camera's yaw, in JAU14 (16384 per turn), as RuneLite's: the client's own value.
     *
     * @return the yaw, or -1 if it can't be read
     */
    int getCameraYaw();

    /**
     * The scale of the world, the zoom value projections multiply by. Mirrors the Java {@code Camera.getScale}.
     *
     * @return the scale, or -1 if it can't be read
     */
    int getScale();

    /**
     * The game canvas's width in the client's own (DPI-scaled) pixels, the units {@link Perspective} returns. Mirrors
     * Lua {@code Graphics.clientDimensions}.
     *
     * @return the width, or -1 if it can't be read
     */
    int getCanvasWidth();

    /**
     * The game canvas's height in the client's own pixels.
     *
     * @return the height, or -1 if it can't be read
     */
    int getCanvasHeight();

    /**
     * The camera as it is now, for projecting points of the top-level scene onto the canvas. Kraken extra, what
     * {@link Perspective} is built on; RuneLite's {@code Perspective} reads the camera getters instead. Mirrors the
     * reads of Lua {@code Graphics.worldToScreenCoord}, done once.
     *
     * @return the projection, or null if the camera or the canvas can't be read
     */
    CameraProjection getCameraProjection();
}
