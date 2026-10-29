package com.kraken.api.internal.reader;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

import java.nio.ByteBuffer;

/**
 * Reads the script engine's stacks off its static runner: the int stack and the object stack (VarValues, a variant
 * whose kind byte says whether it holds an int, a string or a long), each with its size. The stacks only hold values
 * while a script runs, so a read between scripts sees what the last one left. Every key is optional: a build whose
 * mapping lacks them reads empty stacks.
 */
public final class ScriptReader extends GameReader {

    /**
     * How many entries each stack holds at most; a size beyond it is a bad read.
     */
    private static final int MAX_STACK = 1000;

    private final long runnerRva;
    private final int intStack;
    private final int intStackSize;
    private final int objStack;
    private final int objStackSize;
    private final int valueSize;
    private final int valueType;
    private final int kindInt;
    private final int kindString;
    private final int kindLong;
    private final boolean mapped;

    /**
     * Resolves the runner's and the VarValue's layout.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     */
    public ScriptReader(Memory memory, Mapping mapping) {
        super(memory, mapping);
        this.runnerRva = optionalGlobal("scriptRunner");
        this.intStack = optionalOffset("ScriptRunner.intStack");
        this.intStackSize = optionalOffset("ScriptRunner.intStackSize");
        this.objStack = optionalOffset("ScriptRunner.objStack");
        this.objStackSize = optionalOffset("ScriptRunner.objStackSize");
        this.valueSize = optionalOffset("VarValue.size");
        this.valueType = optionalOffset("VarValue.type");
        this.kindInt = mapping.enumValue("varValueType", "INT", -1);
        this.kindString = mapping.enumValue("varValueType", "STRING", -1);
        this.kindLong = mapping.enumValue("varValueType", "LONG", -1);
        this.mapped = runnerRva >= 0 && intStack >= 0 && intStackSize >= 0 && objStack >= 0 && objStackSize >= 0
                && valueSize > 0 && valueType >= 0;
    }

    /**
     * The int stack's size.
     *
     * @return the size, or 0 if it can't be read
     */
    public int intStackSize() {
        return mapped ? size(intStackSize) : 0;
    }

    /**
     * The int stack, oldest first.
     *
     * @return as many ints as the stack holds; empty if it can't be read
     */
    public int[] intStack() {
        int size = intStackSize();
        if (size == 0) {
            return new int[0];
        }
        int[] ints = readInts(runner() + intStack, size);
        return ints == null ? new int[0] : ints;
    }

    /**
     * The object stack's size.
     *
     * @return the size, or 0 if it can't be read
     */
    public int objectStackSize() {
        return mapped ? size(objStackSize) : 0;
    }

    /**
     * The object stack, oldest first: {@code String}s, {@code Integer}s and {@code Long}s, null for other kinds.
     *
     * @return as many entries as the stack holds; empty if it can't be read
     */
    public Object[] objectStack() {
        int size = objectStackSize();
        long base = runner() + objStack;
        ByteBuffer block = size == 0 ? null : readBlock(base, size * valueSize);
        if (block == null) {
            return new Object[0];
        }
        Object[] objects = new Object[size];
        for (int i = 0; i < size; i++) {
            int at = i * valueSize;
            int kind = block.get(at + valueType) & 0xff;
            if (kind == kindInt) {
                objects[i] = block.getInt(at);
            } else if (kind == kindString) {
                objects[i] = structs.string(base + at);
            } else if (kind == kindLong) {
                objects[i] = block.getLong(at);
            }
        }
        return objects;
    }

    private long runner() {
        return memory.moduleBase() + runnerRva;
    }

    private int size(int offset) {
        int size = safeInt(runner() + offset, 0);
        return size < 0 || size > MAX_STACK ? 0 : size;
    }
}
