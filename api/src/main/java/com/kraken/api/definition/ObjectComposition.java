package com.kraken.api.definition;

import com.kraken.api.Client;
import com.kraken.api.internal.Definitions;
import com.kraken.api.internal.NpcDecoder;
import com.kraken.api.internal.ObjectDecoder;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * An object's (scenery's) definition: the client's loc type, config group 6, decoded by {@link ObjectDecoder} from the
 * same bytes the client decodes, including the client's post-steps. Get one from {@link Client#getObjectDefinition}.
 *
 * <p>A snapshot of the definition, which doesn't change while the client runs; only {@link #getActions()} and
 * {@link #getImpostor()} depend on live game state. An id the cache has no file for still gives a definition, with the defaults below and name "", as
 * in the client.
 *
 * <p>An unset param reads -1 or "" (the API doesn't decode the param definitions that hold the defaults).
 */
@Getter
public final class ObjectComposition {

    @Getter(AccessLevel.NONE)
    private final Client client;

    /**
     * The object id (the id on the wiki and in gamevals).
     */
    private final int id;

    /**
     * The object's name, "" for a type without one.
     */
    private final String name;

    @Getter(AccessLevel.NONE)
    private final int[] models;

    @Getter(AccessLevel.NONE)
    private final int[] shapes;

    /**
     * The object's size along x, from its definition (opcode 14): before any rotation. A placed object turned a quarter
     * has its x and y sizes swapped. 1 by default.
     */
    private final int sizeX;

    /**
     * The object's size along y (north), from its definition (opcode 15): before any rotation. 1 by default.
     */
    private final int sizeY;

    /**
     * The clip type: 0 walk-through, 1 (opcode 27), 2 solid (the default). {@link #blocksWalk} is this != 0.
     */
    private final int interactType;

    @Getter(AccessLevel.NONE)
    private final boolean blocksProjectile;

    /**
     * Whether the object can be interacted with (shows its name and ops; Lua's {@code getActive}). Opcode 19 sets it;
     * otherwise the client derives it: true if it has models and its first shape is 10 (a plain scenery object) or it
     * has no shapes, or if any op is set.
     */
    private final boolean active;

    /**
     * Ground contouring: -1 none (the default), 0 (opcode 21), or a value times 256 (opcode 81).
     */
    private final int contouredGround;

    /**
     * Whether the type sets opcode 22.
     */
    private final boolean nonFlatShading;

    /**
     * Whether the type sets opcode 23.
     */
    private final boolean modelClipped;

    /**
     * The object's animation (opcode 24), or -1 if none.
     */
    private final int animationId;

    /**
     * For walls, how far wall decorations sit from the wall (opcode 28), in local units; 16 by default. Mirrors Lua
     * {@code LocType.getWallWidth}.
     */
    private final int wallWidth;

    /**
     * The lighting ambient adjustment (opcode 29), 0 by default.
     */
    private final int ambient;

    /**
     * The lighting contrast adjustment (opcode 39), stored times 5 as the client does; 0 by default.
     */
    private final int contrast;

    @Getter(AccessLevel.NONE)
    private final NpcDecoder.Ops ops;

    @Getter(AccessLevel.NONE)
    private final int[] recolorFrom;

    @Getter(AccessLevel.NONE)
    private final int[] recolorTo;

    @Getter(AccessLevel.NONE)
    private final int[] retextureFrom;

    @Getter(AccessLevel.NONE)
    private final int[] retextureTo;

    /**
     * The object's category (opcode 61), or -1 if none.
     */
    private final int category;

    /**
     * Whether the model is mirrored (opcode 62).
     */
    private final boolean rotated;

    @Getter(AccessLevel.NONE)
    private final boolean shadow;

    /**
     * The model scale along x (opcode 65): 128 = 100%, the default.
     */
    private final int modelSizeX;

    /**
     * The model scale vertically (opcode 66): 128 = 100%, the default.
     */
    private final int modelSizeHeight;

    /**
     * The model scale along y (opcode 67): 128 = 100%, the default.
     */
    private final int modelSizeY;

    /**
     * The minimap scene sprite (opcode 68), or -1 if none.
     */
    private final int mapSceneId;

    /**
     * The map area, the minimap icon (opcode 82), or -1 if none.
     */
    private final int mapIconId;

    /**
     * Which sides the object can be interacted from (opcode 69): a side bitmask, 0 by default.
     */
    private final int blockingMask;

    /**
     * The model offset along x (opcode 70), 0 by default.
     */
    private final int offsetX;

    /**
     * The model offset vertically (opcode 71), 0 by default.
     */
    private final int offsetHeight;

    /**
     * The model offset along y (opcode 72), 0 by default.
     */
    private final int offsetY;

    @Getter(AccessLevel.NONE)
    private final boolean obstructsGround;

    /**
     * Whether the object is hollow (opcode 74): it never blocks movement or projectiles, whatever the other opcodes
     * say.
     */
    private final boolean hollow;

    /**
     * Whether items can be placed on it: opcode 75's value; otherwise 1 if the object blocks movement, else 0.
     */
    private final int supportItems;

    /**
     * The varbit that picks which object this one shows as (opcodes 77/92), or -1 if none. See
     * {@link #getImpostor()}.
     */
    private final int varbitId;

    /**
     * The varp that picks which object this one shows as, used when {@link #getVarbitId()} is -1; -1 if none.
     */
    private final int varPlayerId;

    @Getter(AccessLevel.NONE)
    private final int[] impostorIds;

    /**
     * The ambient sound (opcode 78), or -1 if none.
     */
    private final int ambientSoundId;

    /**
     * The ambient sound's range (opcodes 78 and 79), 0 by default.
     */
    private final int ambientSoundDistance;

    /**
     * Whether the object's animation starts at a random frame: true unless the type has opcode 89.
     */
    private final boolean randomizeAnimStart;

    /**
     * For a ground decoration (opcode 96): how much it raises whatever stands on its tile, in height units; 0 by
     * default. The client's tile-height code (0xeb680 in 241.3) subtracts it from the ground height when this is the
     * tile's ground decoration (heights grow downwards).
     */
    private final int decorHeightOffset;

    /**
     * The type's params (opcode 249): param id to {@code Integer}, {@code Long} or {@code String} value; empty if the
     * type has none.
     */
    private final Map<Integer, Object> params;

    /**
     * A decoded definition. Internal: {@link ObjectDecoder} makes them.
     *
     * @param b      the decoder's fields
     * @param client the client its actions resolve vars against, or null to resolve none
     */
    public ObjectComposition(ObjectDecoder.Builder b, Client client) {
        this.client = client;
        this.id = b.getId();
        this.name = b.getName();
        this.models = b.getModels().clone();
        this.shapes = b.getShapes().clone();
        this.sizeX = b.getWidth();
        this.sizeY = b.getLength();
        this.interactType = b.getInteractType();
        this.blocksProjectile = b.isBlocksProjectile();
        this.active = b.getActive() != 0;
        this.contouredGround = b.getContouredGround();
        this.nonFlatShading = b.isNonFlatShading();
        this.modelClipped = b.isModelClipped();
        this.animationId = b.getAnimationId();
        this.wallWidth = b.getWallWidth();
        this.ambient = b.getAmbient();
        this.contrast = b.getContrast();
        this.ops = b.getOps().freeze();
        this.recolorFrom = NpcDecoder.copy(b.getRecolorFrom());
        this.recolorTo = NpcDecoder.copy(b.getRecolorTo());
        this.retextureFrom = NpcDecoder.copy(b.getRetextureFrom());
        this.retextureTo = NpcDecoder.copy(b.getRetextureTo());
        this.category = b.getCategory();
        this.rotated = b.isRotated();
        this.shadow = b.isShadow();
        this.modelSizeX = b.getModelSizeX();
        this.modelSizeHeight = b.getModelSizeHeight();
        this.modelSizeY = b.getModelSizeY();
        this.mapSceneId = b.getMapSceneId();
        this.mapIconId = b.getMapAreaId();
        this.blockingMask = b.getBlockingMask();
        this.offsetX = b.getOffsetX();
        this.offsetHeight = b.getOffsetHeight();
        this.offsetY = b.getOffsetY();
        this.obstructsGround = b.isObstructsGround();
        this.hollow = b.isHollow();
        this.supportItems = b.getSupportItems();
        this.varbitId = b.getMultiVarbit();
        this.varPlayerId = b.getMultiVarp();
        this.impostorIds = NpcDecoder.copy(b.getMultiChildren());
        this.ambientSoundId = b.getAmbientSoundId();
        this.ambientSoundDistance = b.getAmbientSoundDistance();
        this.randomizeAnimStart = b.isRandomizeAnimStart();
        this.decorHeightOffset = b.getDecorHeightOffset();
        this.params = NpcDecoder.params(b.getParams());
    }

    /**
     * The object's actions ("Open", "Chop down", …), with conditional actions resolved against the current vars as the
     * client resolves them (Lua's {@code LocType.getOps}).
     *
     * @return five entries, index 0 being the first action; null for an empty slot
     */
    public String[] getActions() {
        return client == null ? Definitions.actions(ops.base())
                : Definitions.actions(ops.resolve(client::getVarpValue, client::getVarbitValue));
    }

    /**
     * Whether any action has var-dependent variants.
     *
     * @return true if at least one action has a conditional variant (opcode 101)
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
     * Whether the object blocks movement: its interact type is non-zero. Mirrors Lua {@code LocType.blocksWalk}.
     *
     * @return true if it blocks walking; true by default
     */
    public boolean blocksWalk() {
        return interactType != 0;
    }

    /**
     * Whether the object blocks projectiles. Opcodes 17 and 18 and hollow objects clear it. Mirrors Lua
     * {@code LocType.blocksRange}.
     *
     * @return true if it blocks ranged attacks and line of sight; true by default
     */
    public boolean blocksRange() {
        return blocksProjectile;
    }

    /**
     * Whether the object casts a shadow.
     *
     * @return true unless the type has opcode 64
     */
    public boolean hasShadow() {
        return shadow;
    }

    /**
     * Whether the type sets opcode 73.
     *
     * @return true if it does
     */
    public boolean obstructsGround() {
        return obstructsGround;
    }

    /**
     * The model ids (opcodes 6 and 7).
     *
     * @return a copy of the model ids; empty if none
     */
    public int[] getModels() {
        return models.clone();
    }

    /**
     * The shape of each model (opcode 6).
     *
     * @return a copy of the shapes (10 = plain object, 22 = ground decoration, …); empty for opcode 7 or no models
     */
    public int[] getShapes() {
        return shapes.clone();
    }

    /**
     * The colours replaced on the model (opcode 40), paired with {@link #getRecolorTo()}.
     *
     * @return a copy of the original colours; empty if none
     */
    public int[] getRecolorFrom() {
        return recolorFrom.clone();
    }

    /**
     * The colours {@link #getRecolorFrom()} are replaced with, by position (opcode 40).
     *
     * @return a copy of the replacement colours; empty if none
     */
    public int[] getRecolorTo() {
        return recolorTo.clone();
    }

    /**
     * The textures replaced on the model (opcode 41), paired with {@link #getRetextureTo()}.
     *
     * @return a copy of the original texture ids; empty if none
     */
    public int[] getRetextureFrom() {
        return retextureFrom.clone();
    }

    /**
     * The textures {@link #getRetextureFrom()} are replaced with, by position (opcode 41).
     *
     * @return a copy of the replacement texture ids; empty if none
     */
    public int[] getRetextureTo() {
        return retextureTo.clone();
    }

    /**
     * The object ids this one can show as, indexed by the {@link #getVarbitId()}/{@link #getVarPlayerId()} value; the
     * last entry is the fallback for values past the end (opcodes 92 and 98; -1 for 77 and 97).
     *
     * @return a copy of the child object ids (-1 = nothing); empty if the object doesn't transform
     */
    public int[] getImpostorIds() {
        return impostorIds.clone();
    }

    /**
     * The definition this object shows as now, picked as the client picks it (0x5eaae0 in 241.3): the value of
     * {@link #getVarbitId()}, else of {@link #getVarPlayerId()} (from the varp copy varbits read), indexes
     * {@link #getImpostorIds()}; a value below 0, or past the second-to-last id, takes the last id. The name and the
     * actions the client's menu shows are the shown definition's.
     *
     * @return the shown definition; this definition if the object doesn't transform; null if the pick is -1 (the object
     *         shows nothing), or if this definition was decoded without a client or the shown one can't be read
     */
    public ObjectComposition getImpostor() {
        if (impostorIds.length == 0) {
            return this;
        }
        if (client == null) {
            return null;
        }
        int value = varbitId != -1 ? client.getVarbitValue(varbitId)
                : varPlayerId != -1 ? client.getVarpValue(varPlayerId) : -1;
        int last = impostorIds.length - 1;
        int shown = impostorIds[value < 0 || value >= last ? last : value];
        return shown == -1 ? null : client.getObjectDefinition(shown);
    }

    /**
     * An integer param.
     *
     * @param paramId the param id
     * @return the param's value, or -1 if the definition doesn't set it (or sets a string)
     */
    public int getIntValue(int paramId) {
        return Definitions.intValue(params, paramId);
    }

    /**
     * A string param.
     *
     * @param paramId the param id
     * @return the param's value, or "" if the definition doesn't set it (or sets an integer)
     */
    public String getStringValue(int paramId) {
        return Definitions.stringValue(params, paramId);
    }

    @Override
    public String toString() {
        return "ObjectComposition[" + id + " " + name + "]";
    }
}
