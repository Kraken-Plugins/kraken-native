#include "core/process.h"

#include <windows.h>

namespace kraken::process {
namespace {

uintptr_t g_base = 0;
uint64_t g_imageSize = 0;
int64_t g_qpcFrequency = 0;

} // namespace

bool init() {
    g_base = reinterpret_cast<uintptr_t>(GetModuleHandleW(L"osclient.exe"));
    if (g_base == 0) {
        return false;
    }
    const IMAGE_DOS_HEADER *dos = reinterpret_cast<const IMAGE_DOS_HEADER *>(g_base);
    const IMAGE_NT_HEADERS64 *nt = reinterpret_cast<const IMAGE_NT_HEADERS64 *>(g_base + dos->e_lfanew);
    g_imageSize = nt->OptionalHeader.SizeOfImage;
    LARGE_INTEGER frequency;
    QueryPerformanceFrequency(&frequency);
    g_qpcFrequency = frequency.QuadPart;
    return true;
}

uintptr_t base() {
    return g_base;
}

uint64_t imageSize() {
    return g_imageSize;
}

bool inImage(uint64_t rva) {
    return rva != 0 && rva < g_imageSize;
}

int64_t counterFrequency() {
    return g_qpcFrequency;
}

} // namespace kraken::process
