package com.kraken.api.config;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Raw config files from the client's in-memory config archive (cache index 2). Every config loader
 * ({@code ConfigLoader.<group>}) passes this archive to the same core function, which looks the file up and hands its
 * bytes to the type's decoder. The client keeps every group it has loaded fully decompressed (it never drops a file
 * after decoding it), so any id's bytes can be read here, not only the ~64 recently used types the per-type caches
 * hold.
 *
 * <p>Layout, as the loader core walks it: the archive holds a vector of groups; each group a vector of files; each file
 * a start offset (-1 when absent) and a {@code shared_ptr} to a buffer {@code {length, data}}. A group the client
 * hasn't loaded has no buffers yet; its files read as absent.
 */
public final class ConfigArchive {

    private static final long MAX_GROUPS = 4096;
    private static final long MAX_FILES = 1 << 20;
    private static final long MAX_FILE_BYTES = 1 << 20;

    private final Memory memory;
    private final long archiveRva;
    private final int groupCount;
    private final int groups;
    private final int groupStride;
    private final int groupVersion;
    private final int fileCount;
    private final int files;
    private final int fileStride;
    private final int fileStart;
    private final int fileBuffer;
    private final int bufferLength;
    private final int bufferData;

    public ConfigArchive(Memory memory, Mapping mapping) {
        this.memory = memory;
        this.archiveRva = mapping.global("configCache");
        this.groupCount = mapping.offset("Js5Archive.groupCount");
        this.groups = mapping.offset("Js5Archive.groups");
        this.groupStride = mapping.offset("Js5Group.size");
        this.groupVersion = mapping.offset("Js5Group.version");
        this.fileCount = mapping.offset("Js5Group.fileCount");
        this.files = mapping.offset("Js5Group.files");
        this.fileStride = mapping.offset("Js5File.size");
        this.fileStart = mapping.offset("Js5File.start");
        this.fileBuffer = mapping.offset("Js5File.buffer");
        this.bufferLength = mapping.offset("Packet.length");
        this.bufferData = mapping.offset("Packet.data");
    }

    /** Whether a mapping has everything this reader needs (older mappings predate it). */
    public static boolean supported(Mapping mapping) {
        return mapping.hasGlobal("configCache") && mapping.has("Js5Archive.groups") && mapping.has("Js5Group.files")
                && mapping.has("Js5File.buffer") && mapping.has("Packet.data");
    }

    /** The archive object's address, or 0 if the client hasn't created it. Changes only if the client rebuilds it. */
    public long address() {
        try {
            return memory.readPointer(memory.moduleBase() + archiveRva);
        } catch (MemoryException e) {
            return 0;
        }
    }

    /** How many files a group has slots for (the highest id plus one), or -1 if the group isn't there. */
    public int fileCount(int group) {
        long g = group(group);
        if (g == 0) {
            return -1;
        }
        try {
            long count = memory.readLong(g + fileCount);
            return count < 0 || count > MAX_FILES ? -1 : (int) count;
        } catch (MemoryException e) {
            return -1;
        }
    }

    /**
     * The bytes of one config file (from its start to the end of its buffer; decoders stop at their end opcode), or
     * null if the group or file doesn't exist, isn't loaded, or can't be read.
     */
    public byte[] file(int group, int file) {
        long g = group(group);
        if (g == 0 || file < 0) {
            return null;
        }
        try {
            long count = memory.readLong(g + fileCount);
            long table = memory.readPointer(g + files);
            if (file >= count || count > MAX_FILES || table == 0) {
                return null;
            }
            ByteBuffer entry = block(table + (long) file * fileStride, fileStride);
            long start = entry.getLong(fileStart);
            long buffer = entry.getLong(fileBuffer);
            if (start < 0 || buffer == 0) {
                return null;
            }
            long length = memory.readLong(buffer + bufferLength);
            long data = memory.readPointer(buffer + bufferData);
            if (data == 0 || length <= start || length > MAX_FILE_BYTES) {
                return null;
            }
            return memory.read(data + start, (int) (length - start));
        } catch (MemoryException e) {
            return null;
        }
    }

    /** A group's record, or 0 if the archive or group isn't there. */
    private long group(int group) {
        long archive = address();
        if (archive == 0 || group < 0) {
            return 0;
        }
        try {
            long count = memory.readLong(archive + groupCount);
            long table = memory.readPointer(archive + groups);
            if (group >= count || count > MAX_GROUPS || table == 0) {
                return 0;
            }
            long g = table + (long) group * groupStride;
            return memory.readInt(g + groupVersion) == -1 ? 0 : g;
        } catch (MemoryException e) {
            return 0;
        }
    }

    private ByteBuffer block(long address, int length) {
        return ByteBuffer.wrap(memory.read(address, length)).order(ByteOrder.LITTLE_ENDIAN);
    }
}
