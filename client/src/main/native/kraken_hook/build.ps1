# Builds kraken_hook.dll next to this script. Run from any PowerShell:
#   powershell -ExecutionPolicy Bypass -File build.ps1
#
# It finds a C++ toolchain itself - no need for the "x64 Native Tools" prompt:
#   1. cl.exe if already on PATH;
#   2. otherwise Visual Studio's MSVC, located via vswhere and initialised from vcvars64.bat;
#   3. otherwise a 64-bit MinGW g++ on PATH (e.g. TDM-GCC), static-linked so the injected DLL needs
#      no MinGW runtime DLLs inside osclient.exe.
#
# MinHook (the inline-hooking library) is cloned next to this script on first run.

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

# Pulls the MSVC build environment (cl.exe, headers, libs) into this session from vcvars64.bat.
function Initialize-Msvc {
    $installerDir = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer"
    $vswhere = Join-Path $installerDir "vswhere.exe"
    if (-not (Test-Path $vswhere)) { return $false }
    $install = & $vswhere -products * -latest -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $install) { return $false }
    $vcvars = Join-Path $install "VC\Auxiliary\Build\vcvars64.bat"
    if (-not (Test-Path $vcvars)) { return $false }
    Write-Host "Initialising MSVC from $install"
    # vcvars64.bat calls vswhere internally; put the installer dir on PATH so it resolves quietly.
    $env:PATH = "$installerDir;$env:PATH"
    cmd /c "`"$vcvars`" && set" | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { Set-Item -Path ("Env:" + $matches[1]) -Value $matches[2] }
    }
    return [bool](Get-Command cl.exe -ErrorAction SilentlyContinue)
}

if (-not (Test-Path "$here\minhook")) {
    Write-Host "Cloning MinHook..."
    git clone --depth 1 https://github.com/TsudaKageyu/minhook.git "$here\minhook"
}

$cSources = @(
    "minhook\src\buffer.c",
    "minhook\src\hook.c",
    "minhook\src\trampoline.c",
    "minhook\src\hde\hde32.c",
    "minhook\src\hde\hde64.c"
)

$haveCl = [bool](Get-Command cl.exe -ErrorAction SilentlyContinue)
if (-not $haveCl) { $haveCl = Initialize-Msvc }

if ($haveCl) {
    Write-Host "Compiling kraken_hook.dll with MSVC (cl.exe)..."
    & cl /nologo /LD /EHsc /O2 /std:c++17 /DNDEBUG /D_WIN32_WINNT=0x0601 /I "minhook\include" `
        kraken_hook.cpp $cSources /Fe:kraken_hook.dll /link ws2_32.lib opengl32.lib gdi32.lib user32.lib
    if ($LASTEXITCODE -ne 0) { throw "Build failed (cl exit $LASTEXITCODE)." }
}
elseif (Get-Command g++ -ErrorAction SilentlyContinue) {
    Write-Host "cl.exe not found; compiling with MinGW g++ (static)..."
    # -x c forces the MinHook .c files to compile as C; -x none restores auto-detection.
    & g++ -shared -O2 -std=c++17 -DNDEBUG -D_WIN32_WINNT=0x0601 -static -static-libgcc -static-libstdc++ `
        -I "minhook\include" kraken_hook.cpp -x c $cSources -x none `
        -o kraken_hook.dll -lws2_32 -lopengl32 -lgdi32 -luser32
    if ($LASTEXITCODE -ne 0) { throw "Build failed (g++ exit $LASTEXITCODE)." }
}
else {
    throw @"
No C++ toolchain found.
  - Visual Studio / Build Tools: install the "Desktop development with C++" workload from
    https://visualstudio.microsoft.com/downloads/ (Build Tools is the smaller, free option), or
  - MinGW-w64 (64-bit g++) on PATH.
"@
}

Write-Host "Built $here\kraken_hook.dll"
