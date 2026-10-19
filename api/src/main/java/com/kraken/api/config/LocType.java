package com.kraken.api.config;

import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Mirrors the Lua {@code osrs.LocType}: one loc (scenery) definition, config group 6, decoded by
 * {@link LocTypeDecoder} from the same bytes the client decodes, including the client's post-steps. Immutable.
 *
 * <p>The Lua object only holds the id and reloads the type on every call; a Java {@code LocType} is a snapshot of the
 * definition, which doesn't change while the client runs. Only {@link #getOps(IntUnaryOperator, IntUnaryOperator)}
 * depends on live game state.
 *
 * <p>An id the cache has no file for still gives a type, with the defaults below and name "", as in the client.
 */
public final class LocType implements TypeData {

    private final int id;
    private final String name;
    private final int[] models;
    private final int[] shapes;
    private final int width;
    private final int length;
    private final int interactType;
    private final boolean blocksProjectile;
    private final boolean active;
    private final int contouredGround;
    private final boolean nonFlatShading;
    private final boolean modelClipped;
    private final int animationId;
    private final int wallWidth;
    private final int ambient;
    private final int contrast;
    private final NpcTypeDecoder.Ops ops;
    private final int[] recolorFrom;
    private final int[] recolorTo;
    private final int[] retextureFrom;
    private final int[] retextureTo;
    private final int category;
    private final boolean rotated;
    private final boolean shadow;
    private final int modelSizeX;
    private final int modelSizeHeight;
    private final int modelSizeY;
    private final int mapSceneId;
    private final int mapAreaId;
    private final int blockingMask;
    private final int offsetX;
    private final int offsetHeight;
    private final int offsetY;
    private final boolean obstructsGround;
    private final boolean hollow;
    private final int supportItems;
    private final int multiVarbit;
    private final int multiVarp;
    private final int[] multiChildren;
    private final int ambientSoundId;
    private final int ambientSoundDistance;
    private final boolean randomizeAnimStart;
    private final int decorHeightOffset;
    private final Map<Integer, Object> params;

    private LocType(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.models = b.models.clone();
        this.shapes = b.shapes.clone();
        this.width = b.width;
        this.length = b.length;
        this.interactType = b.interactType;
        this.blocksProjectile = b.blocksProjectile;
        this.active = b.active != 0;
        this.contouredGround = b.contouredGround;
        this.nonFlatShading = b.nonFlatShading;
        this.modelClipped = b.modelClipped;
        this.animationId = b.animationId;
        this.wallWidth = b.wallWidth;
        this.ambient = b.ambient;
        this.contrast = b.contrast;
        this.ops = b.ops.freeze();
        this.recolorFrom = NpcTypeDecoder.copy(b.recolorFrom);
        this.recolorTo = NpcTypeDecoder.copy(b.recolorTo);
        this.retextureFrom = NpcTypeDecoder.copy(b.retextureFrom);
        this.retextureTo = NpcTypeDecoder.copy(b.retextureTo);
        this.category = b.category;
        this.rotated = b.rotated;
        this.shadow = b.shadow;
        this.modelSizeX = b.modelSizeX;
        this.modelSizeHeight = b.modelSizeHeight;
        this.modelSizeY = b.modelSizeY;
        this.mapSceneId = b.mapSceneId;
        this.mapAreaId = b.mapAreaId;
        this.blockingMask = b.blockingMask;
        this.offsetX = b.offsetX;
        this.offsetHeight = b.offsetHeight;
        this.offsetY = b.offsetY;
        this.obstructsGround = b.obstructsGround;
        this.hollow = b.hollow;
        this.supportItems = b.supportItems;
        this.multiVarbit = b.multiVarbit;
        this.multiVarp = b.multiVarp;
        this.multiChildren = NpcTypeDecoder.copy(b.multiChildren);
        this.ambientSoundId = b.ambientSoundId;
        this.ambientSoundDistance = b.ambientSoundDistance;
        this.randomizeAnimStart = b.randomizeAnimStart;
        this.decorHeightOffset = b.decorHeightOffset;
        this.params = NpcTypeDecoder.params(b.params);
    }

    /**
     * The loc type id (the id on the wiki and in gamevals).
     *
     * @return the type id
     */
    @Override
    public int getId() {
        return id;
    }

    /**
     * Mirrors {@code getName}: the decoded name.
     *
     * @return the loc's name, "" when the type has none
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * The entity type (the client's override returns 2).
     *
     * @return always {@link TypecodeEntityType#LOC}
     */
    @Override
    public TypecodeEntityType getEntityType() {
        return TypecodeEntityType.LOC;
    }

    /**
     * Mirrors {@code blocksWalk}: whether its interact type is non-zero (it blocks movement).
     *
     * @return true if the loc blocks movement; true by default
     */
    public boolean blocksWalk() {
        return interactType != 0;
    }

    /**
     * Mirrors {@code blocksRange} (the Lua docs call it {@code blocksRanged} and say it returns a number; the client
     * returns a boolean): whether it blocks projectiles. Opcodes 17 and 18 and hollow locs clear it.
     *
     * @return true if the loc blocks projectiles; true by default
     */
    public boolean blocksRanged() {
        return blocksProjectile;
    }

    /**
     * Mirrors {@code getWidth}: size along x before rotation (opcode 14).
     *
     * @return the width in tiles, 1 by default
     */
    public int getWidth() {
        return width;
    }

    /**
     * Mirrors {@code getLength}: size along z before rotation (opcode 15).
     *
     * @return the length in tiles, 1 by default
     */
    public int getLength() {
        return length;
    }

    /**
     * Mirrors {@code getWallWidth}: for walls, how far wall decorations sit from the wall (opcode 28).
     *
     * @return the offset in fine units (1/128 tile), 16 by default
     */
    public int getWallWidth() {
        return wallWidth;
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
     * Mirrors {@code getActive}: whether the loc can be interacted with (shows its name and ops). Opcode 19 sets it;
     * otherwise the client derives it: true if it has models and its first shape is 10 (a plain scenery object) or
     * it has no shapes, or if any op is set.
     *
     * @return true if the loc is interactive
     */
    public boolean getActive() {
        return active;
    }

    /**
     * The five ops as written in the definition, without the var-dependent variants. This is what {@code getOps}
     * returns when no variant applies; see {@link #getOps(IntUnaryOperator, IntUnaryOperator)}.
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
     * @return true if at least one op has a conditional variant (opcode 101)
     */
    public boolean hasConditionalOps() {
        return ops.hasAlternatives();
    }

    /**
     * Model ids (opcodes 6 and 7). Not a Lua method.
     *
     * @return a copy of the model ids; empty if none
     */
    public int[] getModels() {
        return models.clone();
    }

    /**
     * The shape of each model (opcode 6). Not a Lua method.
     *
     * @return a copy of the shapes (10 = plain object, 22 = ground decoration, ...); empty for opcode 7 or no models
     */
    public int[] getShapes() {
        return shapes.clone();
    }

    /**
     * Clip type. {@link #blocksWalk} is this != 0. Not a Lua method.
     *
     * @return 0 walk-through, 1 (opcode 27), 2 solid (the default)
     */
    public int getInteractType() {
        return interactType;
    }

    /**
     * Ground contouring. Not a Lua method.
     *
     * @return -1 none (the default), 0 (opcode 21), or a value times 256 (opcode 81)
     */
    public int getContouredGround() {
        return contouredGround;
    }

    /**
     * Opcode 22. Not a Lua method.
     *
     * @return true if the type sets opcode 22
     */
    public boolean isNonFlatShading() {
        return nonFlatShading;
    }

    /**
     * Opcode 23. Not a Lua method.
     *
     * @return true if the type sets opcode 23
     */
    public boolean isModelClipped() {
        return modelClipped;
    }

    /**
     * Animation (opcode 24). Not a Lua method.
     *
     * @return the animation id, -1 if none
     */
    public int getAnimationId() {
        return animationId;
    }

    /**
     * Lighting ambient adjustment (opcode 29). Not a Lua method.
     *
     * @return the adjustment, 0 by default
     */
    public int getAmbient() {
        return ambient;
    }

    /**
     * Lighting contrast adjustment (opcode 39). Not a Lua method.
     *
     * @return the adjustment, stored times 5 as the client does; 0 by default
     */
    public int getContrast() {
        return contrast;
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
     * Category (opcode 61). Not a Lua method.
     *
     * @return the category id, -1 if none
     */
    public int getCategory() {
        return category;
    }

    /**
     * Opcode 62: the model is mirrored. Not a Lua method.
     *
     * @return true if the model is mirrored
     */
    public boolean isRotated() {
        return rotated;
    }

    /**
     * Whether it casts a shadow. Not a Lua method.
     *
     * @return true unless the type has opcode 64
     */
    public boolean hasShadow() {
        return shadow;
    }

    /**
     * Model scale along x (opcode 65). Not a Lua method.
     *
     * @return the scale, 128 = 100% (the default)
     */
    public int getModelSizeX() {
        return modelSizeX;
    }

    /**
     * Model scale vertically (opcode 66). Not a Lua method.
     *
     * @return the scale, 128 = 100% (the default)
     */
    public int getModelSizeHeight() {
        return modelSizeHeight;
    }

    /**
     * Model scale along z (opcode 67). Not a Lua method.
     *
     * @return the scale, 128 = 100% (the default)
     */
    public int getModelSizeY() {
        return modelSizeY;
    }

    /**
     * Minimap scene sprite (opcode 68). Not a Lua method.
     *
     * @return the sprite id, -1 if none
     */
    public int getMapSceneId() {
        return mapSceneId;
    }

    /**
     * Map area / minimap icon (opcode 82). Not a Lua method.
     *
     * @return the map area id, -1 if none
     */
    public int getMapAreaId() {
        return mapAreaId;
    }

    /**
     * Which sides it can be interacted from (opcode 69). Not a Lua method.
     *
     * @return the side bitmask, 0 by default
     */
    public int getBlockingMask() {
        return blockingMask;
    }

    /**
     * Model offset along x (opcode 70). Not a Lua method.
     *
     * @return the offset, 0 by default
     */
    public int getOffsetX() {
        return offsetX;
    }

    /**
     * Model offset vertically (opcode 71). Not a Lua method.
     *
     * @return the offset, 0 by default
     */
    public int getOffsetHeight() {
        return offsetHeight;
    }

    /**
     * Model offset along z (opcode 72). Not a Lua method.
     *
     * @return the offset, 0 by default
     */
    public int getOffsetY() {
        return offsetY;
    }

    /**
     * Opcode 73. Not a Lua method.
     *
     * @return true if the type sets opcode 73
     */
    public boolean obstructsGround() {
        return obstructsGround;
    }

    /**
     * Opcode 74: hollow locs never block movement or projectiles, whatever the other opcodes say. Not a Lua method.
     *
     * @return true for a hollow loc
     */
    public boolean isHollow() {
        return hollow;
    }

    /**
     * Whether items can be placed on it. Not a Lua method.
     *
     * @return opcode 75's value; otherwise 1 if the loc blocks movement, else 0
     */
    public int getSupportItems() {
        return supportItems;
    }

    /**
     * The varbit that picks which loc this one shows as (opcodes 77/92). See {@link #getMultiChildren}. Not a Lua
     * method.
     *
     * @return the varbit id, -1 if none
     */
    public int getMultiVarbit() {
        return multiVarbit;
    }

    /**
     * The varp that picks which loc this one shows as, used when {@link #getMultiVarbit} is -1. Not a Lua method.
     *
     * @return the varp id, -1 if none
     */
    public int getMultiVarp() {
        return multiVarp;
    }

    /**
     * The loc ids this one can show as, indexed by the varbit/varp value; the last entry is the fallback for values
     * past the end (opcode 92; -1 for 77). Not a Lua method.
     *
     * @return a copy of the child loc ids (-1 = nothing); empty if the loc doesn't transform
     */
    public int[] getMultiChildren() {
        return multiChildren.clone();
    }

    /**
     * Ambient sound (opcode 78). Not a Lua method.
     *
     * @return the sound id, -1 if none
     */
    public int getAmbientSoundId() {
        return ambientSoundId;
    }

    /**
     * Ambient sound range (opcodes 78 and 79). Not a Lua method.
     *
     * @return the range, 0 by default
     */
    public int getAmbientSoundDistance() {
        return ambientSoundDistance;
    }

    /**
     * Whether its animation starts at a random frame. Not a Lua method.
     *
     * @return true unless the type has opcode 89
     */
    public boolean isRandomizeAnimStart() {
        return randomizeAnimStart;
    }

    /**
     * For a ground decoration (opcode 96): how much it raises whatever stands on its tile. The client's tile-height
     * code (0xeb680 in 241.3) subtracts it from the ground height when this is the tile's ground decoration (heights
     * grow downwards). Not a Lua method.
     *
     * @return the offset in height units, 0 by default
     */
    public int getDecorHeightOffset() {
        return decorHeightOffset;
    }

    @Override
    public String toString() {
        return "LocType[" + id + " " + name + "]";
    }

    /**
     * Mutable fields with the client constructor's defaults (0x5ecdb0); the decoder fills it in.
     */
    static final class Builder {
        final int id;
        String name = "";
        int[] models = new int[0];
        int[] shapes = new int[0];
        int width = 1;
        int length = 1;
        int interactType = 2;
        boolean blocksProjectile = true;
        int active = -1;
        int contouredGround = -1;
        boolean nonFlatShading;
        boolean modelClipped;
        int animationId = -1;
        int wallWidth = 16;
        int ambient;
        int contrast;
        final NpcTypeDecoder.Ops ops = new NpcTypeDecoder.Ops();
        int[] recolorFrom;
        int[] recolorTo;
        int[] retextureFrom;
        int[] retextureTo;
        int category = -1;
        boolean rotated;
        boolean shadow = true;
        int modelSizeX = 128;
        int modelSizeHeight = 128;
        int modelSizeY = 128;
        int mapSceneId = -1;
        int mapAreaId = -1;
        int blockingMask;
        int offsetX;
        int offsetHeight;
        int offsetY;
        boolean obstructsGround;
        boolean hollow;
        int supportItems = -1;
        int multiVarbit = -1;
        int multiVarp = -1;
        int[] multiChildren;
        int ambientSoundId = -1;
        int ambientSoundDistance;
        int ambientSoundRetain;
        boolean randomizeAnimStart = true;
        int decorHeightOffset;
        Map<Integer, Object> params;

        Builder(int id) {
            this.id = id;
        }

        LocType build() {
            return new LocType(this);
        }
    }
}
