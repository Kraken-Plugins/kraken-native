package com.kraken.api.memory;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

/**
 * {@link Memory} backed by {@code ReadProcessMemory} against a live {@code osclient.exe} process.
 * Opens the process read-only, locates the module's base address (randomised by ASLR each run) via a
 * Toolhelp snapshot, and serves bounded reads. Never writes.
 */
public final class ProcessMemory implements Memory, AutoCloseable {

    // Lowest/highest plausible user-space addresses on x64 Windows; cheap guard against obviously bad pointers.
    private static final long MIN_ADDRESS = 0x10000L;
    private static final long MAX_ADDRESS = 0x7FFFFFFFFFFFL;
    private static final int STILL_ACTIVE = 259;

    private final HANDLE process;
    private final long moduleBase;
    private final long moduleSize;

    private ProcessMemory(HANDLE process, long moduleBase, long moduleSize) {
        this.process = process;
        this.moduleBase = moduleBase;
        this.moduleSize = moduleSize;
    }

    /**
     * Attaches to {@code pid} and finds the base of the named module (e.g. {@code "osclient.exe"}).
     *
     * @throws MemoryException if the process cannot be opened for reading or the module is not loaded.
     */
    public static ProcessMemory attach(int pid, String moduleName) {
        Kernel32 k32 = Kernel32.INSTANCE;
        long[] module = findModule(k32, pid, moduleName); // {base, size}

        HANDLE process = k32.OpenProcess(WinNT.PROCESS_VM_READ | WinNT.PROCESS_QUERY_INFORMATION, false, pid);
        if (process == null) {
            throw new MemoryException("OpenProcess failed for pid " + pid + " (error " + k32.GetLastError() + ")");
        }
        return new ProcessMemory(process, module[0], module[1]);
    }

    private static long[] findModule(Kernel32 k32, int pid, String moduleName) {
        int flags = Tlhelp32.TH32CS_SNAPMODULE.intValue() | Tlhelp32.TH32CS_SNAPMODULE32.intValue();
        HANDLE snapshot = k32.CreateToolhelp32Snapshot(new DWORD(flags), new DWORD(pid));
        if (snapshot == null || WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) {
            throw new MemoryException("CreateToolhelp32Snapshot failed for pid " + pid + " (error " + k32.GetLastError() + ")");
        }
        try {
            Tlhelp32.MODULEENTRY32W entry = new Tlhelp32.MODULEENTRY32W();
            boolean more = k32.Module32FirstW(snapshot, entry);
            while (more) {
                if (moduleName.equalsIgnoreCase(entry.szModule())) {
                    return new long[]{Pointer.nativeValue(entry.modBaseAddr), entry.modBaseSize.longValue()};
                }
                more = k32.Module32NextW(snapshot, entry);
            }
        } finally {
            k32.CloseHandle(snapshot);
        }
        throw new MemoryException("Module " + moduleName + " not found in pid " + pid);
    }

    @Override
    public byte[] read(long address, int length) {
        if (address < MIN_ADDRESS || address > MAX_ADDRESS || length <= 0) {
            throw new MemoryException("Refusing to read " + length + " bytes at 0x" + Long.toHexString(address));
        }
        com.sun.jna.Memory buffer = new com.sun.jna.Memory(length);
        try {
            IntByReference read = new IntByReference();
            boolean ok = Kernel32.INSTANCE.ReadProcessMemory(process, new Pointer(address), buffer, length, read);
            if (!ok || read.getValue() != length) {
                throw new MemoryException("ReadProcessMemory failed at 0x" + Long.toHexString(address)
                        + " len " + length + " (error " + Kernel32.INSTANCE.GetLastError() + ")");
            }
            return buffer.getByteArray(0, length);
        } finally {
            buffer.close();
        }
    }

    @Override
    public long moduleBase() {
        return moduleBase;
    }

    @Override
    public long moduleSize() {
        return moduleSize;
    }

    @Override
    public boolean isValid() {
        IntByReference exit = new IntByReference();
        return Kernel32.INSTANCE.GetExitCodeProcess(process, exit) && exit.getValue() == STILL_ACTIVE;
    }

    @Override
    public void close() {
        Kernel32.INSTANCE.CloseHandle(process);
    }
}
