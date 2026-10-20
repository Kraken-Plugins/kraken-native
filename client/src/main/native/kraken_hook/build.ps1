# Builds kraken_hook.dll next to this script.
#
# Run from an "x64 Native Tools Command Prompt for VS" so cl.exe and the x64 toolchain are on PATH:
#   powershell -ExecutionPolicy Bypass -File build.ps1
#
# On first run this clones MinHook (a small, well-known inline-hooking library that handles the x64
# instruction relocation for the trampoline) next to this script.

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
    throw "cl.exe not found. Open an 'x64 Native Tools Command Prompt for VS' and run this from there."
}

if (-not (Test-Path "$here\minhook")) {
    Write-Host "Cloning MinHook..."
    git clone --depth 1 https://github.com/TsudaKageyu/minhook.git "$here\minhook"
}

$src = @(
    "kraken_hook.cpp",
    "minhook\src\buffer.c",
    "minhook\src\hook.c",
    "minhook\src\trampoline.c",
    "minhook\src\hde\hde32.c",
    "minhook\src\hde\hde64.c"
)

Write-Host "Compiling kraken_hook.dll..."
& cl /nologo /LD /EHsc /O2 /DNDEBUG /I "minhook\include" $src /Fe:kraken_hook.dll /link ws2_32.lib

if ($LASTEXITCODE -ne 0) {
    throw "Build failed (cl exit $LASTEXITCODE)."
}
Write-Host "Built $here\kraken_hook.dll"
