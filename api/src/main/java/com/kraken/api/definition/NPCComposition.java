package com.kraken.api.definition;

import com.kraken.api.Client;
import com.kraken.api.actor.NPC;
import com.kraken.api.internal.Definitions;
import com.kraken.api.internal.NpcDecoder;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * An NPC type's definition, as RuneLite's {@code NPCComposition}: config group 9, decoded by {@link NpcDecoder} from
 * the same bytes the client decodes, as the client's loader builds it. Get one from {@link Client#getNpcDefinition} or
 * {@link NPC#getComposition}.
 *
 * <p>A snapshot of the definition, which doesn't change while the client runs; only {@link #getActions()} depends on
 * live game state. An id the cache has no file for still gives a definition, with the defaults below and name "", as
 * in the client.
 *
 * <p>RuneLite's {@code ParamHolder} methods are here directly; an unset param reads -1 or "" (Kraken doesn't decode
 * the param definitions that hold RuneLite's defaults).
 */
@Getter
public final class NPCComposition {

    @Getter(AccessLevel.NONE)
    private final Client client;

    /**
     * The type id (the id on the wiki and in gamevals).
     */
    private final int id;

    /**
     * The type's name (Lua's {@code getName} and {@code ClientOp.ncName}): "null" where the client's data says so,
     * "" for a type without one.
     */
    private final String name;

    /**
     * How many tiles wide (and long) the NPC is, 1 by default.
     */
    private final int size;

    /**
     * The type's category (opcode 18, Lua's {@code NpcType.getType}), or -1 if none.
     */
    private final int category;

    /**
     * The combat level shown in the menu (opcode 95), or -1 if none.
     */
    private final int combatLevel;

    @Getter(AccessLevel.NONE)
    private final int[] stats;

    @Getter(AccessLevel.NONE)
    private final NpcDecoder.Ops ops;

    /**
     * The type's params (opcode 249): param id to {@code Integer}, {@code Long} or {@code String} value; empty if the
     * type has none. Kraken extra.
     */
    private final Map<Integer, Object> params;

    @Getter(AccessLevel.NONE)
    private final int[] models;

    @Getter(AccessLevel.NONE)
    private final int[] chatheadModels;

    /**
     * The idle animation (opcode 13), or -1 if none.
     */
    private final int standingAnimation;

    /**
     * The walk animation (opcodes 14 and 17), or -1 if none.
     */
    private final int walkingAnimation;

    /**
     * The run animation (opcodes 114 and 115), or -1 if none.
     */
    private final int runAnimation;

    /**
     * The crawl animation (opcodes 116 and 117), or -1 if none.
     */
    private final int crawlAnimation;

    @Getter(AccessLevel.NONE)
    private final int[] recolorFrom;

    @Getter(AccessLevel.NONE)
    private final int[] recolorTo;

    @Getter(AccessLevel.NONE)
    private final int[] retextureFrom;

    @Getter(AccessLevel.NONE)
    private final int[] retextureTo;

    /**
     * The horizontal model scale (opcode 97): 128 = 100%, the default.
     */
    private final int widthScale;

    /**
     * The vertical model scale (opcode 98): 128 = 100%, the default.
     */
    private final int heightScale;

    /**
     * The lighting ambient adjustment (opcode 100), 0 by default. Kraken extra.
     */
    private final int ambient;

    /**
     * The lighting contrast adjustment (opcode 101), stored times 5 as the client does; 0 by default. Kraken extra.
     */
    private final int contrast;

    @Getter(AccessLevel.NONE)
    private final int[] headIconArchives;

    @Getter(AccessLevel.NONE)
    private final int[] headIconIndices;

    /**
     * The turn speed (opcode 103), 32 by default.
     */
    private final int rotationSpeed;

    /**
     * The varbit that picks which NPC this one shows as (opcodes 106/118), or -1 if none. See {@link #getConfigs()}.
     */
    private final int varbitId;

    /**
     * The varp that picks which NPC this one shows as, used when {@link #getVarbitId()} is -1; -1 if none.
     */
    private final int varpIndex;

    @Getter(AccessLevel.NONE)
    private final int[] configs;

    /**
     * Whether the NPC shows on the minimap (opcode 93 hides it).
     */
    private final boolean minimapVisible;

    /**
     * Whether the NPC can be clicked (opcode 107 turns it off).
     */
    private final boolean interactible;

    /**
     * Whether the NPC turns to face what it interacts with (opcode 109 turns it off). Kraken extra.
     */
    private final boolean rotationFlag;

    /**
     * Whether the type is a follower, such as a pet (opcode 122).
     */
    private final boolean follower;

    /**
     * Whether a follower's ops are pushed down the menu (opcode 123). Kraken extra.
     */
    private final boolean lowPriorityFollowerOps;

    /**
     * The model height override (opcode 124), or -1 if none. Kraken extra.
     */
    private final int height;

    /**
     * The footprint size (opcode 126); when the type doesn't set it, what the client derives:
     * {@code (int) (size * 128 * 0.4f)}.
     */
    private final int footprintSize;

    /**
     * The draw-order class: 1 normally, 0 for types with opcode 99, 2 for opcode 111. Kraken extra.
     */
    private final int renderPriority;

    /**
     * A decoded definition. Internal: {@link NpcDecoder} makes them.
     *
     * @param b      the decoder's fields
     * @param client the client its actions resolve vars against, or null to resolve none
     */
    public NPCComposition(NpcDecoder.Builder b, Client client) {
        this.client = client;
        this.id = b.getId();
        this.name = b.getName();
        this.size = b.getSize();
        this.category = b.getCategory();
        this.combatLevel = b.getCombatLevel();
        this.stats = new int[]{b.getAttack(), b.getDefence(), b.getStrength(), b.getHitpoints(), b.getRanged(),
                b.getMagic()};
        this.ops = b.getOps().freeze();
        this.params = NpcDecoder.params(b.getParams());
        this.models = NpcDecoder.copy(b.getModels());
        this.chatheadModels = NpcDecoder.copy(b.getChatheadModels());
        this.standingAnimation = b.getStandingAnimation();
        this.walkingAnimation = b.getWalkAnimation();
        this.runAnimation = b.getRunAnimation();
        this.crawlAnimation = b.getCrawlAnimation();
        this.recolorFrom = NpcDecoder.copy(b.getRecolorFrom());
        this.recolorTo = NpcDecoder.copy(b.getRecolorTo());
        this.retextureFrom = NpcDecoder.copy(b.getRetextureFrom());
        this.retextureTo = NpcDecoder.copy(b.getRetextureTo());
        this.widthScale = b.getWidthScale();
        this.heightScale = b.getHeightScale();
        this.ambient = b.getAmbient();
        this.contrast = b.getContrast();
        this.headIconArchives = NpcDecoder.copy(b.getHeadIconArchives());
        this.headIconIndices = NpcDecoder.copy(b.getHeadIconIndices());
        this.rotationSpeed = b.getRotationSpeed();
        this.varbitId = b.getMultiVarbit();
        this.varpIndex = b.getMultiVarp();
        this.configs = NpcDecoder.copy(b.getMultiChildren());
        this.minimapVisible = b.isMinimapVisible();
        this.interactible = b.isInteractable();
        this.rotationFlag = b.isRotationFlag();
        this.follower = b.isFollower();
        this.lowPriorityFollowerOps = b.isLowPriorityFollowerOps();
        this.height = b.getHeight();
        this.footprintSize = b.getFootprintSize();
        this.renderPriority = b.getRenderPriority();
    }

    /**
     * The NPC's actions ("Talk-to", "Attack", …), with conditional actions resolved against the current vars as the
     * client resolves them (Lua's {@code NpcType.getOps}).
     *
     * @return five entries, index 0 being the first action; null for an empty slot, as in RuneLite
     */
    public String[] getActions() {
        return client == null ? Definitions.actions(ops.base())
                : Definitions.actions(ops.resolve(client::getVarpValue, client::getVarbitValue));
    }

    /**
     * Whether any action has var-dependent variants. Kraken extra.
     *
     * @return true if at least one action has a conditional variant (opcode 252)
     */
    public boolean hasConditionalActions() {
        return ops.hasAlternatives();
    }

    /**
     * The five ops as written in the definition, without the var-dependent variants.
     *
     * @return op1..op5 in order, "" for an empty slot
     */
    List<String> getOps() {
        return ops.base();
    }

    /**
     * The five ops with each one's first variant whose varp or varbit value is within its range.
     *
     * @param varp   reads a varp the way the client does here (its varbit-source array)
     * @param varbit reads a varbit
     * @return op1..op5 in order, "" for an empty slot
     */
    List<String> getOps(IntUnaryOperator varp, IntUnaryOperator varbit) {
        return ops.resolve(varp, varbit);
    }

    /**
     * The type's six combat levels (opcodes 74-79), each 1 unless the type sets it.
     *
     * @return attack, defence, strength, hitpoints, ranged and magic, in that order
     */
    public int[] getStats() {
        return stats.clone();
    }

    /**
     * The model ids the NPC is built from (opcode 61).
     *
     * @return a copy of the model ids; empty if none
     */
    public int[] getModels() {
        return models.clone();
    }

    /**
     * The model ids of its chat head (opcode 62).
     *
     * @return a copy of the chat head model ids; empty if none
     */
    public int[] getChatheadModels() {
        return chatheadModels.clone();
    }

    /**
     * The colours replaced on the model (opcode 40), paired with {@link #getRecolorTo()}. Kraken extra.
     *
     * @return a copy of the original colours; empty if none
     */
    public int[] getRecolorFrom() {
        return recolorFrom.clone();
    }

    /**
     * The colours {@link #getRecolorFrom()} are replaced with, by position (opcode 40). Kraken extra.
     *
     * @return a copy of the replacement colours; empty if none
     */
    public int[] getRecolorTo() {
        return recolorTo.clone();
    }

    /**
     * The textures replaced on the model (opcode 41), paired with {@link #getRetextureTo()}. Kraken extra.
     *
     * @return a copy of the original texture ids; empty if none
     */
    public int[] getRetextureFrom() {
        return retextureFrom.clone();
    }

    /**
     * The textures {@link #getRetextureFrom()} are replaced with, by position (opcode 41). Kraken extra.
     *
     * @return a copy of the replacement texture ids; empty if none
     */
    public int[] getRetextureTo() {
        return retextureTo.clone();
    }

    /**
     * The head icon sprite archives (opcode 102), paired with {@link #getHeadIconSpriteIndexes()}.
     *
     * @return a copy of the archive ids, -1 for unused slots; empty if none
     */
    public int[] getHeadIconArchiveIds() {
        return headIconArchives.clone();
    }

    /**
     * The head icon sprite indices within their archives (opcode 102).
     *
     * @return a copy of the sprite indices, -1 for unused slots; empty if none
     */
    public int[] getHeadIconSpriteIndexes() {
        return headIconIndices.clone();
    }

    /**
     * The NPC ids this one can show as, indexed by the {@link #getVarbitId()}/{@link #getVarpIndex()} value; the last
     * entry is the fallback for values past the end (opcode 118; -1 for 106). The client does not apply this to a
     * loaded NPC's type, which is the id the server sent; {@link NPC#getTransformedComposition()} applies it.
     *
     * @return a copy of the child NPC ids (-1 = hidden); empty if the NPC doesn't transform
     */
    public int[] getConfigs() {
        return configs.clone();
    }

    /**
     * An integer param, as RuneLite's {@code ParamHolder.getIntValue}.
     *
     * @param paramId the param id
     * @return the param's value, or -1 if the definition doesn't set it (or sets a string)
     */
    public int getIntValue(int paramId) {
        return Definitions.intValue(params, paramId);
    }

    /**
     * A string param, as RuneLite's {@code ParamHolder.getStringValue}.
     *
     * @param paramId the param id
     * @return the param's value, or "" if the definition doesn't set it (or sets an integer)
     */
    public String getStringValue(int paramId) {
        return Definitions.stringValue(params, paramId);
    }

    @Override
    public String toString() {
        return "NPCComposition[" + id + " " + name + "]";
    }
}
