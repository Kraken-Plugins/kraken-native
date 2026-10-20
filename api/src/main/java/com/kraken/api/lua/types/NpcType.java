package com.kraken.api.lua.types;

import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Mirrors the Lua {@code osrs.NpcType}: one NPC definition (config group 9), decoded by {@link NpcTypeDecoder} from
 * the same bytes the client decodes. Immutable.
 *
 * <p>The Lua object only holds the id: every method loads the type again and reads one field, so a Lua NpcType never
 * goes stale. A Java {@code NpcType} is a snapshot of the definition, which doesn't change while the client runs; only
 * {@link #getOps(IntUnaryOperator, IntUnaryOperator)} depends on live game state.
 *
 * <p>An id the cache has no file for still gives a type, with the defaults below and name "", as in the client.
 */
public final class NpcType implements TypeData {

    /**
     * Mirrors the table {@code osrs.NpcType.getStats} returns: the six combat levels, each 1 unless set.
     *
     * @param attack    Attack level (opcode 74)
     * @param defence   Defence level (opcode 75)
     * @param strength  Strength level (opcode 76)
     * @param hitpoints Hitpoints level (opcode 77)
     * @param ranged    Ranged level (opcode 78)
     * @param magic     Magic level (opcode 79)
     */
    public record Stats(int attack, int defence, int strength, int hitpoints, int ranged, int magic) {
    }

    private final int id;
    private final String name;
    private final int size;
    private final int category;
    private final int combatLevel;
    private final Stats stats;
    private final NpcTypeDecoder.Ops ops;
    private final Map<Integer, Object> params;
    private final int[] models;
    private final int[] chatheadModels;
    private final int standingAnimation;
    private final int walkAnimation;
    private final int runAnimation;
    private final int crawlAnimation;
    private final int[] recolorFrom;
    private final int[] recolorTo;
    private final int[] retextureFrom;
    private final int[] retextureTo;
    private final int widthScale;
    private final int heightScale;
    private final int ambient;
    private final int contrast;
    private final int[] headIconArchives;
    private final int[] headIconIndices;
    private final int rotationSpeed;
    private final int multiVarbit;
    private final int multiVarp;
    private final int[] multiChildren;
    private final boolean minimapVisible;
    private final boolean interactable;
    private final boolean rotationFlag;
    private final boolean follower;
    private final boolean lowPriorityFollowerOps;
    private final int height;
    private final int footprintSize;
    private final int renderPriority;

    private NpcType(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.size = b.size;
        this.category = b.category;
        this.combatLevel = b.combatLevel;
        this.stats = new Stats(b.attack, b.defence, b.strength, b.hitpoints, b.ranged, b.magic);
        this.ops = b.ops.freeze();
        this.params = NpcTypeDecoder.params(b.params);
        this.models = NpcTypeDecoder.copy(b.models);
        this.chatheadModels = NpcTypeDecoder.copy(b.chatheadModels);
        this.standingAnimation = b.standingAnimation;
        this.walkAnimation = b.walkAnimation;
        this.runAnimation = b.runAnimation;
        this.crawlAnimation = b.crawlAnimation;
        this.recolorFrom = NpcTypeDecoder.copy(b.recolorFrom);
        this.recolorTo = NpcTypeDecoder.copy(b.recolorTo);
        this.retextureFrom = NpcTypeDecoder.copy(b.retextureFrom);
        this.retextureTo = NpcTypeDecoder.copy(b.retextureTo);
        this.widthScale = b.widthScale;
        this.heightScale = b.heightScale;
        this.ambient = b.ambient;
        this.contrast = b.contrast;
        this.headIconArchives = NpcTypeDecoder.copy(b.headIconArchives);
        this.headIconIndices = NpcTypeDecoder.copy(b.headIconIndices);
        this.rotationSpeed = b.rotationSpeed;
        this.multiVarbit = b.multiVarbit;
        this.multiVarp = b.multiVarp;
        this.multiChildren = NpcTypeDecoder.copy(b.multiChildren);
        this.minimapVisible = b.minimapVisible;
        this.interactable = b.interactable;
        this.rotationFlag = b.rotationFlag;
        this.follower = b.follower;
        this.lowPriorityFollowerOps = b.lowPriorityFollowerOps;
        this.height = b.height;
        this.footprintSize = b.footprintSize;
        this.renderPriority = b.renderPriority;
    }

    /**
     * The NPC type id (the id on the wiki and in gamevals).
     *
     * @return the type id
     */
    @Override
    public int getId() {
        return id;
    }

    /**
     * Mirrors {@code getName} (and {@code ClientOp.ncName}): the decoded name.
     *
     * @return the NPC's name, "" when the type has none
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * The entity type (the client's override returns 1).
     *
     * @return always {@link TypecodeEntityType#NPC}
     */
    @Override
    public TypecodeEntityType getEntityType() {
        return TypecodeEntityType.NPC;
    }

    /**
     * Mirrors {@code getType}: the NPC's category (opcode 18).
     *
     * @return the category id, -1 if the type has none
     */
    public int getType() {
        return category;
    }

    /**
     * Mirrors {@code getSize}: how many tiles wide (and long) the NPC is.
     *
     * @return the size in tiles, 1 by default
     */
    public int getSize() {
        return size;
    }

    /**
     * Mirrors {@code getParams}: the type's params (opcode 249).
     *
     * @return param id to value ({@code Integer}, {@code Long} or {@code String}); empty if the type has none
     */
    public Map<Integer, Object> getParams() {
        return params;
    }

    /**
     * Mirrors {@code getStats}: the six combat levels (opcodes 74-79).
     *
     * @return the levels, each 1 unless the type sets it
     */
    public Stats getStats() {
        return stats;
    }

    /**
     * The five ops as written in the definition, without the var-dependent variants. This is what {@code getOps}
     * returns for an NPC with no variants or when none applies; see {@link #getOps(IntUnaryOperator, IntUnaryOperator)}
     * for the live lookup.
     *
     * @return op1..op5 in order, "" for an empty slot
     */
    public List<String> getOps() {
        return ops.base();
    }

    /**
     * Mirrors {@code getOps} ({@code op1}..{@code op5}): each op's text, or its first variant whose varp or varbit
     * value is within the variant's range at the moment of the call.
     *
     * @param varp   reads a varp the way the client does here (its varbit-source array, {@code global.varpsBits})
     * @param varbit reads a varbit ({@code ClientState.getVarbit})
     * @return op1..op5 in order, "" for an empty slot
     */
    public List<String> getOps(IntUnaryOperator varp, IntUnaryOperator varbit) {
        return ops.resolve(varp, varbit);
    }

    /**
     * Whether any op has var-dependent variants (if not, {@link #getOps()} is the whole answer).
     *
     * @return true if at least one op has a conditional variant (opcode 252)
     */
    public boolean hasConditionalOps() {
        return ops.hasAlternatives();
    }

    /**
     * Combat level shown in the menu (opcode 95). Not a Lua method.
     *
     * @return the combat level, -1 if none
     */
    public int getCombatLevel() {
        return combatLevel;
    }

    /**
     * Model ids the NPC is built from (opcode 61). Not a Lua method.
     *
     * @return a copy of the model ids; empty if none
     */
    public int[] getModels() {
        return models.clone();
    }

    /**
     * Model ids of its chat head (opcode 62). Not a Lua method.
     *
     * @return a copy of the chat head model ids; empty if none
     */
    public int[] getChatheadModels() {
        return chatheadModels.clone();
    }

    /**
     * Idle animation (opcode 13). Not a Lua method.
     *
     * @return the animation id, -1 if none
     */
    public int getStandingAnimation() {
        return standingAnimation;
    }

    /**
     * Walk animation (opcodes 14 and 17). Not a Lua method.
     *
     * @return the animation id, -1 if none
     */
    public int getWalkAnimation() {
        return walkAnimation;
    }

    /**
     * Run animation (opcodes 114 and 115). Not a Lua method.
     *
     * @return the animation id, -1 if none
     */
    public int getRunAnimation() {
        return runAnimation;
    }

    /**
     * Crawl animation (opcodes 116 and 117). Not a Lua method.
     *
     * @return the animation id, -1 if none
     */
    public int getCrawlAnimation() {
        return crawlAnimation;
    }

    /**
     * Colours replaced on the model (opcode 40), paired with {@link #getRecolorTo()}. Not a Lua method.
     *
     * @return a copy of the original colours; empty if none
     */
    public int[] getRecolorFrom() {
        return recolorFrom.clone();
    }

    /**
     * The colours {@link #getRecolorFrom()} are replaced with, by position (opcode 40). Not a Lua method.
     *
     * @return a copy of the replacement colours; empty if none
     */
    public int[] getRecolorTo() {
        return recolorTo.clone();
    }

    /**
     * Textures replaced on the model (opcode 41), paired with {@link #getRetextureTo()}. Not a Lua method.
     *
     * @return a copy of the original texture ids; empty if none
     */
    public int[] getRetextureFrom() {
        return retextureFrom.clone();
    }

    /**
     * The textures {@link #getRetextureFrom()} are replaced with, by position (opcode 41). Not a Lua method.
     *
     * @return a copy of the replacement texture ids; empty if none
     */
    public int[] getRetextureTo() {
        return retextureTo.clone();
    }

    /**
     * Horizontal model scale (opcode 97). Not a Lua method.
     *
     * @return the scale, 128 = 100% (the default)
     */
    public int getWidthScale() {
        return widthScale;
    }

    /**
     * Vertical model scale (opcode 98). Not a Lua method.
     *
     * @return the scale, 128 = 100% (the default)
     */
    public int getHeightScale() {
        return heightScale;
    }

    /**
     * Lighting ambient adjustment (opcode 100). Not a Lua method.
     *
     * @return the adjustment, 0 by default
     */
    public int getAmbient() {
        return ambient;
    }

    /**
     * Lighting contrast adjustment (opcode 101). Not a Lua method.
     *
     * @return the adjustment, stored times 5 as the client does; 0 by default
     */
    public int getContrast() {
        return contrast;
    }

    /**
     * Head icon sprite archives (opcode 102), paired with {@link #getHeadIconIndices()}. Not a Lua method.
     *
     * @return a copy of the archive ids, -1 for unused slots; empty if none
     */
    public int[] getHeadIconArchives() {
        return headIconArchives.clone();
    }

    /**
     * Head icon sprite indices within their archives (opcode 102). Not a Lua method.
     *
     * @return a copy of the sprite indices, -1 for unused slots; empty if none
     */
    public int[] getHeadIconIndices() {
        return headIconIndices.clone();
    }

    /**
     * Turn speed (opcode 103). Not a Lua method.
     *
     * @return the turn speed, 32 by default
     */
    public int getRotationSpeed() {
        return rotationSpeed;
    }

    /**
     * The varbit that picks which NPC this one shows as (opcodes 106/118). See {@link #getMultiChildren}. Not a Lua
     * method.
     *
     * @return the varbit id, -1 if none
     */
    public int getMultiVarbit() {
        return multiVarbit;
    }

    /**
     * The varp that picks which NPC this one shows as, used when {@link #getMultiVarbit} is -1. Not a Lua method.
     *
     * @return the varp id, -1 if none
     */
    public int getMultiVarp() {
        return multiVarp;
    }

    /**
     * The NPC ids this one can show as, indexed by the varbit/varp value; the last entry is the fallback for values
     * past the end (opcode 118; -1 for 106). The client does not apply this to {@code Npc.type}: a loaded NPC's type is
     * the id the server sent. Not a Lua method.
     *
     * @return a copy of the child NPC ids (-1 = hidden); empty if the NPC doesn't transform
     */
    public int[] getMultiChildren() {
        return multiChildren.clone();
    }

    /**
     * Whether the NPC shows on the minimap (opcode 93 hides it). Not a Lua method.
     *
     * @return true unless the type hides it
     */
    public boolean isMinimapVisible() {
        return minimapVisible;
    }

    /**
     * Whether the NPC can be clicked (opcode 107 turns it off). Not a Lua method.
     *
     * @return true unless the type turns it off
     */
    public boolean isInteractable() {
        return interactable;
    }

    /**
     * Whether the NPC turns to face what it interacts with (opcode 109 turns it off). Not a Lua method.
     *
     * @return true unless the type turns it off
     */
    public boolean getRotationFlag() {
        return rotationFlag;
    }

    /**
     * Whether the type is a follower, such as a pet (opcode 122). Not a Lua method.
     *
     * @return true for a follower type
     */
    public boolean isFollower() {
        return follower;
    }

    /**
     * Whether a follower's ops are pushed down the menu (opcode 123). Not a Lua method.
     *
     * @return true if the type sets it
     */
    public boolean hasLowPriorityFollowerOps() {
        return lowPriorityFollowerOps;
    }

    /**
     * Model height override (opcode 124). Not a Lua method.
     *
     * @return the height, -1 if none
     */
    public int getHeight() {
        return height;
    }

    /**
     * Footprint size (opcode 126). Not a Lua method.
     *
     * @return the footprint; when the type doesn't set it, what the client derives: {@code (int) (size * 128 * 0.4f)}
     */
    public int getFootprintSize() {
        return footprintSize;
    }

    /**
     * Draw-order class. Not a Lua method.
     *
     * @return 1 normally, 0 for types with opcode 99, 2 for opcode 111
     */
    public int getRenderPriority() {
        return renderPriority;
    }

    @Override
    public String toString() {
        return "NpcType[" + id + " " + name + "]";
    }

    /**
     * Mutable fields with the client constructor's defaults (0x6067f0); the decoder fills it in.
     */
    static final class Builder {
        final int id;
        String name = "";
        int size = 1;
        int category = -1;
        int combatLevel = -1;
        int attack = 1;
        int defence = 1;
        int strength = 1;
        int hitpoints = 1;
        int ranged = 1;
        int magic = 1;
        final NpcTypeDecoder.Ops ops = new NpcTypeDecoder.Ops();
        Map<Integer, Object> params;
        int[] models;
        int[] chatheadModels;
        int standingAnimation = -1;
        int walkAnimation = -1;
        int idleRotateLeftAnimation = -1;
        int idleRotateRightAnimation = -1;
        int rotate180Animation = -1;
        int rotateLeftAnimation = -1;
        int rotateRightAnimation = -1;
        int runAnimation = -1;
        int runRotate180Animation = -1;
        int runRotateLeftAnimation = -1;
        int runRotateRightAnimation = -1;
        int crawlAnimation = -1;
        int crawlRotate180Animation = -1;
        int crawlRotateLeftAnimation = -1;
        int crawlRotateRightAnimation = -1;
        int[] recolorFrom;
        int[] recolorTo;
        int[] retextureFrom;
        int[] retextureTo;
        int widthScale = 128;
        int heightScale = 128;
        int ambient;
        int contrast;
        int[] headIconArchives;
        int[] headIconIndices;
        int rotationSpeed = 32;
        int multiVarbit = -1;
        int multiVarp = -1;
        int[] multiChildren;
        boolean minimapVisible = true;
        boolean interactable = true;
        boolean rotationFlag = true;
        boolean follower;
        boolean lowPriorityFollowerOps;
        int height = -1;
        int footprintSize = -1;
        int renderPriority = 1;

        Builder(int id) {
            this.id = id;
        }

        NpcType build() {
            return new NpcType(this);
        }
    }
}
