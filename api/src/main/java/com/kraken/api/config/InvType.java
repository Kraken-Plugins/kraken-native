package com.kraken.api.config;

import java.util.Map;

/**
 * An inventory's definition (config group 5).
 *
 * @param size   how many slots the inventory has ({@code ClientOp.invSize}); 0 when the definition doesn't say
 * @param params the definition's params (usually empty)
 */
public record InvType(int size, Map<Integer, Object> params) {

    /**
     * What the client uses for an id with no definition.
     */
    public static final InvType DEFAULT = new InvType(0, Map.of());

    public InvType {
        params = Map.copyOf(params);
    }

    /**
     * Decodes an inventory file as the client does: opcode 2 is {@code u16 size}, 249 the params block; the client
     * skips any other opcode without reading operands, so this does too.
     *
     * @param data the file's bytes, from its first opcode
     * @return the definition (size 0 and no params if the file sets neither)
     * @throws ConfigFormatException if the file ends before its end opcode
     */
    public static InvType decode(byte[] data) {
        ConfigBuffer buffer = new ConfigBuffer(data);
        int size = 0;
        Map<Integer, Object> params = Map.of();
        for (int opcode = buffer.g1(); opcode != 0; opcode = buffer.g1()) {
            if (opcode == 2) {
                size = buffer.g2();
            } else if (opcode == 249) {
                params = buffer.params();
            }
        }
        return new InvType(size, params);
    }
}
