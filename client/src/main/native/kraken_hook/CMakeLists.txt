# kraken_hook.dll, the DLL the Kraken client injects into osclient.exe. README.md covers what it does and
# how to build it; the Gradle build (client/native-hook.gradle) runs it through CMakePresets.json.
cmake_minimum_required(VERSION 3.25)

# Link the C runtime statically (/MT), so the DLL needs no MSVC runtime DLLs inside the game. Set before any
# target is created, so MinHook is built the same way.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
# Write debug info to a .pdb next to the DLL in every configuration, so a debugger attached to the game can
# show the DLL's functions and lines.
set(CMAKE_MSVC_DEBUG_INFORMATION_FORMAT ProgramDatabase)

project(kraken_hook LANGUAGES C CXX)

if(NOT WIN32 OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "kraken_hook.dll is loaded into the 64-bit Windows client: build it for 64-bit Windows.")
endif()

include(FetchContent)

# MinHook, the inline-hooking library, at a pinned commit; the download is checked against its hash
# (README.md, "Updating MinHook"). SOURCE_SUBDIR names a folder that doesn't exist, so CMake only downloads
# it: MinHook's own CMakeLists.txt targets CMake 3.5, so the minhook target below builds it instead.
FetchContent_Declare(minhook
    URL https://github.com/TsudaKageyu/minhook/archive/8af6b4acae5a9388fd742b56fa79ece89d96f823.tar.gz
    URL_HASH SHA256=6c0334d09607da1c4a34a68b5febf0cf24c5810cae842eda13c9c24ea4196adb
    SOURCE_SUBDIR do-not-build
)
FetchContent_MakeAvailable(minhook)

add_library(minhook STATIC
    ${minhook_SOURCE_DIR}/src/buffer.c
    ${minhook_SOURCE_DIR}/src/hook.c
    ${minhook_SOURCE_DIR}/src/trampoline.c
    ${minhook_SOURCE_DIR}/src/hde/hde64.c
)
target_include_directories(minhook SYSTEM PUBLIC ${minhook_SOURCE_DIR}/include)

add_library(kraken_hook SHARED
    src/dllmain.cpp
    src/protocol.h
    src/connection.h
    src/connection.cpp
    src/raises.h
    src/raises.cpp
    src/calls.h
    src/calls.cpp
    src/pump.h
    src/pump.cpp
    src/scripts.h
    src/scripts.cpp
    src/menus.h
    src/menus.cpp
    src/keys.h
    src/keys.cpp
    src/core/frame_reader.h
    src/core/guard.h
    src/core/hooks.h
    src/core/hooks.cpp
    src/core/memory.h
    src/core/memory.cpp
    src/core/process.h
    src/core/process.cpp
    src/core/ring.h
    src/core/ring.cpp
    src/overlay/gl.h
    src/overlay/gl.cpp
    src/overlay/gl_state.h
    src/overlay/gl_state.cpp
    src/overlay/overlay.h
    src/overlay/overlay.cpp
    src/overlay/surface.h
    src/overlay/surface.cpp
)
target_include_directories(kraken_hook PRIVATE src)
target_compile_features(kraken_hook PRIVATE cxx_std_17)
target_compile_definitions(kraken_hook PRIVATE
    WIN32_LEAN_AND_MEAN # keeps windows.h from pulling in Winsock 1, which clashes with winsock2.h
    NOMINMAX            # keeps windows.h's min and max macros from breaking std::min and std::max
    _WIN32_WINNT=0x0601 # the Windows 7 API
)
target_link_libraries(kraken_hook PRIVATE minhook ws2_32 opengl32 user32)
set_target_properties(kraken_hook PROPERTIES
    CXX_EXTENSIONS OFF
    # kraken_hook.dll, not MinGW's libkraken_hook.dll
    PREFIX ""
    # bin/<config>/kraken_hook.dll (and its .pdb) whatever the generator, so Gradle knows where to find it
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin/$<CONFIG>"
)

if(MSVC)
    # /MP compiles the files in parallel
    target_compile_options(kraken_hook PRIVATE /W4 /permissive- /utf-8 /MP)
    # /DEBUG writes the .pdb but turns off the linker's dead-code removal, so turn that back on outside Debug
    target_link_options(kraken_hook PRIVATE /DEBUG $<$<NOT:$<CONFIG:Debug>>:/OPT:REF /OPT:ICF>)
    # Whole-program optimisation, so calls between the source files inline as within one
    set_property(TARGET kraken_hook PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE ON)
else()
    target_compile_options(kraken_hook PRIVATE -Wall -Wextra)
    # No MinGW runtime DLLs inside the game, and no exports: the DLL is only ever loaded, never linked against
    target_link_options(kraken_hook PRIVATE -static -static-libgcc -static-libstdc++ -Wl,--exclude-all-symbols)
endif()
