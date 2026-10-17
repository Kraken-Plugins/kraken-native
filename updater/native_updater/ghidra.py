"""Ghidra headless export: runs analyzeHeadless when GHIDRA_INSTALL_DIR is configured, else prints the command."""
import os
import shutil
import subprocess
from pathlib import Path

from . import model

SCRIPT_DIR = Path(__file__).resolve().parent.parent / "ghidra"
PINNED_GHIDRA = "12.1"


def paths(cache_root, sha):
    base = Path(cache_root) / "cache" / sha
    return {"base": base, "project": base / "ghidra", "export": base / "export", "db": base / "program.db"}


def headless_command(ghidra_dir, project_dir, binary, export_dir, reanalyse=True):
    exe = Path(ghidra_dir) / "support" / ("analyzeHeadless.bat" if os.name == "nt" else "analyzeHeadless")
    cmd = [str(exe), str(project_dir), "osclient"]
    if reanalyse:
        cmd += ["-import", str(binary)]
    else:
        cmd += ["-process", Path(binary).name, "-noanalysis"]
    cmd += ["-scriptPath", str(SCRIPT_DIR), "-postScript", "ExportProgramModel.java", str(export_dir), "-analysisTimeoutPerFile", "7200"]
    return cmd


def ensure_model(binary, sha, cache_root, log=print, ghidra_dir=None, java_home=None):
    """Return the program.db path, producing it if needed. Returns None when Ghidra is not configured."""
    p = paths(cache_root, sha)
    if p["db"].exists():
        return p["db"]
    if not (p["export"] / "insns.tsv.gz").exists():
        ghidra_dir = ghidra_dir or os.environ.get("GHIDRA_INSTALL_DIR")
        java_home = java_home or os.environ.get("GHIDRA_JAVA_HOME") or os.environ.get("JAVA_HOME")
        project_exists = (p["project"] / "osclient.gpr").exists()
        cmd = headless_command(ghidra_dir or "<GHIDRA_INSTALL_DIR>", p["project"], binary, p["export"], reanalyse=not project_exists)
        if not ghidra_dir:
            log("Ghidra export missing. Run the following (or set GHIDRA_INSTALL_DIR to let the updater run it):")
            log("  " + " ".join(f'"{c}"' if " " in c else c for c in cmd))
            log(f"then: python -m native_updater build-model --binary {binary}")
            return None
        p["project"].mkdir(parents=True, exist_ok=True)
        p["export"].mkdir(parents=True, exist_ok=True)
        env = dict(os.environ)
        if java_home:
            env["JAVA_HOME"] = java_home
            env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
        env.setdefault("MAXMEM", "12G")
        log(f"Running Ghidra headless (this takes 10-15 minutes on a fresh import): {' '.join(cmd)}")
        res = subprocess.run(cmd, env=env, capture_output=True, text=True)
        tail = "\n".join((res.stdout + res.stderr).splitlines()[-15:])
        if res.returncode != 0 or not (p["export"] / "insns.tsv.gz").exists():
            raise RuntimeError(f"Ghidra export failed (exit {res.returncode}):\n{tail}")
        log(tail)
    log(f"Building {p['db']} from the export")
    model.build(p["export"], binary, p["db"])
    return p["db"]
