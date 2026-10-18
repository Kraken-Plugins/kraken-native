"""Windows paths that work whether the updater runs on Windows or inside WSL.

The client, the Jagex Launcher install and the updater cache all live on the Windows side. Under WSL the updater is a
Linux Python: it sees C:\\ as /mnt/c and has its own home directory, often with a different user name. These helpers
translate between the two so defaults and user-supplied Windows paths work in both places.
"""
import functools
import os
import platform
import re
import subprocess
from pathlib import Path

_DRIVE = re.compile(r"^([A-Za-z]):[\\/]")


@functools.lru_cache(maxsize=None)
def is_wsl():
    return os.name != "nt" and ("WSL_DISTRO_NAME" in os.environ or "microsoft" in platform.uname().release.lower())


def _wslpath(flag, path):
    try:
        out = subprocess.run(["wslpath", flag, str(path)], capture_output=True, text=True, timeout=10, check=True).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    return out or None


def local(path):
    """A path this Python can open. On WSL, C:\\x\\y becomes /mnt/c/x/y; every other path is returned unchanged."""
    s = str(path)
    if not (is_wsl() and _DRIVE.match(s)):
        return Path(path)
    translated = _wslpath("-u", s)
    if translated is None:  # wslpath missing: assume the default /mnt automount root
        translated = f"/mnt/{s[0].lower()}/" + s[3:].replace("\\", "/")
    return Path(translated)


@functools.lru_cache(maxsize=None)
def windows_home():
    """The Windows user's profile folder (C:\\Users\\<name>) as a local path; the home directory off Windows and WSL."""
    if is_wsl():
        try:
            out = subprocess.run(["cmd.exe", "/d", "/c", "echo %USERPROFILE%"], capture_output=True, text=True,
                                 errors="replace", timeout=15).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            out = ""
        if _DRIVE.match(out) and local(out).is_dir():
            return local(out)
    return Path.home()
