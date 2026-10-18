package com.kraken.api.memory;

/** Thrown when a cross-process read fails (unmapped address, process exited, access denied). */
public class MemoryException extends RuntimeException {
    public MemoryException(String message) {
        super(message);
    }
}
