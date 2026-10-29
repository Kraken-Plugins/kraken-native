// SEH guards the pointer dereferences a capture plan does and every call into the client: a bad
// pointer or a faulting function must raise rather than crash the client. MSVC (the build's
// preferred toolchain) supports it directly; under MinGW these run unguarded.
//
// A guarded block sets a local bool named faulted when it faults, so the function declares one
// first. MSVC refuses __try in a function that holds C++ objects with destructors (error C2712),
// which is why every guard sits in a small function of its own.
#pragma once

#include <windows.h>

#if defined(_MSC_VER)
#define GUARD_BEGIN __try {
#define GUARD_END } __except (EXCEPTION_EXECUTE_HANDLER) { faulted = true; }
#else
#define GUARD_BEGIN {
#define GUARD_END }
#endif
