package com.kraken.client.hook;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.WinDef.HMODULE;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Injects a DLL into a running process with the classic {@code CreateRemoteThread} +
 * {@code LoadLibraryA} technique: allocate a buffer in the target, write the DLL path into it, then
 * make the target call {@code LoadLibraryA} on that path so the loader maps and runs the DLL.
 *
 * <p>This is the only part of the client that writes to the game process. It is used solely to load
 * {@code kraken_hook.dll}; the DLL, not this class, installs the hook.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class HookInjector {

    private static final int PROCESS_CREATE_THREAD = 0x0002;
    private static final int PROCESS_VM_OPERATION = 0x0008;
    private static final int PROCESS_VM_READ = 0x0010;
    private static final int PROCESS_VM_WRITE = 0x0020;
    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int ACCESS = PROCESS_CREATE_THREAD | PROCESS_VM_OPERATION
            | PROCESS_VM_READ | PROCESS_VM_WRITE | PROCESS_QUERY_INFORMATION;

    private static final int MEM_COMMIT = 0x1000;
    private static final int MEM_RESERVE = 0x2000;
    private static final int MEM_RELEASE = 0x8000;
    private static final int PAGE_READWRITE = 0x04;
    private static final int INFINITE = 0xFFFFFFFF;

    /**
     * The few Kernel32 calls injection needs. Declared here rather than reusing JNA's
     * {@code platform.win32.Kernel32} so {@code CreateRemoteThread} can take a raw {@link Pointer}
     * start address (the remote address of {@code LoadLibraryA}) instead of a same-process callback.
     */
    private interface K32 extends StdCallLibrary {
        K32 INSTANCE = Native.load("kernel32", K32.class);

        HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);

        Pointer VirtualAllocEx(HANDLE hProcess, Pointer lpAddress, SIZE_T dwSize, int flAllocationType, int flProtect);

        boolean WriteProcessMemory(HANDLE hProcess, Pointer lpBaseAddress, Pointer lpBuffer, SIZE_T nSize,
                                   IntByReference lpNumberOfBytesWritten);

        boolean VirtualFreeEx(HANDLE hProcess, Pointer lpAddress, SIZE_T dwSize, int dwFreeType);

        HMODULE GetModuleHandleW(WString lpModuleName);

        Pointer GetProcAddress(HMODULE hModule, String lpProcName);

        HANDLE CreateRemoteThread(HANDLE hProcess, Pointer lpThreadAttributes, SIZE_T dwStackSize,
                                  Pointer lpStartAddress, Pointer lpParameter, int dwCreationFlags, Pointer lpThreadId);

        int WaitForSingleObject(HANDLE hHandle, int dwMilliseconds);

        boolean CloseHandle(HANDLE hObject);
    }

    /**
     * Loads {@code dll} into the target process and waits for its loader thread to finish.
     *
     * @param pid the target process id
     * @param dll the absolute path to the DLL to load
     * @throws IllegalStateException if any Win32 step fails
     */
    static void inject(int pid, Path dll) {
        K32 k32 = K32.INSTANCE;
        HANDLE process = k32.OpenProcess(ACCESS, false, pid);
        if (process == null) {
            throw new IllegalStateException("OpenProcess failed (error " + Native.getLastError() + ")");
        }
        try {
            byte[] path = (dll + "\0").getBytes(StandardCharsets.US_ASCII);
            SIZE_T size = new SIZE_T(path.length);

            Pointer remote = k32.VirtualAllocEx(process, null, size, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
            if (remote == null) {
                throw new IllegalStateException("VirtualAllocEx failed (error " + Native.getLastError() + ")");
            }
            try {
                try (Memory buffer = new Memory(path.length)) {
                    buffer.write(0, path, 0, path.length);
                    if (!k32.WriteProcessMemory(process, remote, buffer, size, new IntByReference())) {
                        throw new IllegalStateException("WriteProcessMemory failed (error " + Native.getLastError() + ")");
                    }
                }

                // kernel32 loads at the same base in every process this boot, so LoadLibraryA's
                // address in our process is also valid in the target.
                HMODULE kernel = k32.GetModuleHandleW(new WString("kernel32.dll"));
                Pointer loadLibrary = k32.GetProcAddress(kernel, "LoadLibraryA");
                if (loadLibrary == null) {
                    throw new IllegalStateException("GetProcAddress(LoadLibraryA) failed (error " + Native.getLastError() + ")");
                }

                HANDLE thread = k32.CreateRemoteThread(process, null, new SIZE_T(0), loadLibrary, remote, 0, null);
                if (thread == null) {
                    throw new IllegalStateException("CreateRemoteThread failed (error " + Native.getLastError() + ")");
                }
                k32.WaitForSingleObject(thread, INFINITE);
                k32.CloseHandle(thread);
            } finally {
                // LoadLibraryA has copied the path by now, so the remote buffer can go.
                k32.VirtualFreeEx(process, remote, new SIZE_T(0), MEM_RELEASE);
            }
        } finally {
            k32.CloseHandle(process);
        }
    }
}
