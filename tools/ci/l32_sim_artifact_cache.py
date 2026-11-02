#!/usr/bin/env python3
"""Fail-closed, content-verified RTL and Verilator build reuse.

This is a *build* cache, never a substitute for executing a Linux test. The
historical L32 name is kept because Makefile.l32-linux-boot also drives RV64 V2.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = 1


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def files_under(directory: Path, patterns: tuple[str, ...]) -> list[Path]:
    if not directory.exists():
        return []
    return sorted({p for pattern in patterns for p in directory.rglob(pattern) if p.is_file()})


def identities(paths: list[Path]) -> list[tuple[str, str]]:
    # Include the actual source contents, including local dirty/untracked files.
    return [(str(path.resolve()), digest(path)) for path in sorted(set(paths))]


def source_recipe(args: argparse.Namespace) -> dict:
    paths = files_under(ROOT / "src/main/scala", ("*.scala", "*.sc", "*.sv"))
    paths += [p for p in (ROOT / "build.mill", ROOT / "mill") if p.is_file()]
    if not paths:
        raise ValueError("no Chisel elaboration inputs found")
    return {"elaborate_main": args.elaborate_main, "sources": identities(paths)}


def rtl_files(rtl_dir: Path) -> list[Path]:
    return sorted(p for p in rtl_dir.glob("*.sv") if p.is_file())


def cpu_identity() -> str:
    """Conservatively fence -march=native binaries from other host CPUs."""
    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.is_file():
        first = cpuinfo.read_text(errors="replace").split("\n\n", 1)[0]
        return "\n".join(
            line for line in first.splitlines()
            if line.startswith(("vendor_id", "model name", "flags", "Features", "CPU implementer"))
        )
    return platform.processor() or platform.machine()


def tool_identity(command: str) -> dict:
    words = shlex.split(command)
    if not words:
        raise ValueError("empty tool command")
    exe = shutil.which(words[0])
    if not exe:
        raise ValueError(f"missing tool: {words[0]}")
    proc = subprocess.run(
        [exe, "--version"], check=True, capture_output=True, text=True, timeout=10
    )
    return {"path": str(Path(exe).resolve()), "version": proc.stdout.strip() or proc.stderr.strip()}


def sim_recipe(args: argparse.Namespace) -> dict:
    rtl_dir = Path(args.rtl_dir)
    svs = rtl_files(rtl_dir)
    if not svs:
        raise ValueError(f"no generated SystemVerilog in {rtl_dir}")
    # Generated Verilog is part of the identity, not just the source commit.
    runner = Path(args.runner)
    if runner.is_absolute() or not runner.parts or ".." in runner.parts or runner.parts[0] != "sim":
        raise ValueError("simulator runner must be a repository-owned sim/ source")
    paths = [
        ROOT / runner,
        ROOT / "sim/l32_opensbi_runtime.h",
        ROOT / "tools/ci/l32_sim_artifact_cache.py",
    ]
    # Include every repository simulator header. Multiple historical RV64
    # variants use different -I shim directories; do not let an older shim
    # change silently reuse a binary built with the previous header contents.
    paths += files_under(ROOT / "sim", ("*.h", "*.hpp", "*.inc"))
    paths.append(ROOT / "Makefile.l32-linux-boot")
    if not all(path.is_file() for path in paths):
        raise ValueError("missing simulator build recipe input")
    return {
        # Chisel sources qualify a separate RTL stage. A changed Scala file
        # must not force C++ recompilation when generated SV is byte-identical.
        "generated_sv": [(path.name, digest(path)) for path in svs],
        "host_sources": identities(paths),
        "runner": args.runner,
        "top": args.top,
        "verilator": args.verilator,
        "verilator_tool": tool_identity(args.verilator),
        "verilator_model_opt": args.verilator_model_opt,
        "verilator_threads": args.verilator_threads,
        "verilator_make_opt": args.verilator_make_opt,
        "sim_cxxflags": args.sim_cxxflags,
        "cxx_command": args.cxx,
        "cxx": tool_identity(args.cxx),
        "build_environment": {
            name: os.environ.get(name, "")
            for name in ("CC", "CXX", "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS")
        },
        "host": {"system": platform.system(), "machine": platform.machine()},
        "native_cpu": cpu_identity() if "-march=native" in args.sim_cxxflags else None,
    }


def key_for(recipe: dict) -> str:
    data = json.dumps(recipe, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def cache_path(args: argparse.Namespace) -> Path:
    if args.stage == "rtl":
        return Path(args.rtl_dir) / ".aethercore-rtl-qualified.json"
    return Path(args.obj_dir) / ".aethercore-sim-qualified.json"


def artifacts(args: argparse.Namespace) -> list[Path]:
    if args.stage == "rtl":
        result = rtl_files(Path(args.rtl_dir))
        if not result:
            raise ValueError("no generated SystemVerilog to qualify")
        return result
    if not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", args.top):
        raise ValueError("invalid Verilator top name")
    executable = Path(args.obj_dir) / f"V{args.top}"
    if not executable.is_file() or not os.access(executable, os.X_OK):
        raise ValueError("Verilator executable missing or not executable")
    return [executable]


def recipe_for(args: argparse.Namespace) -> dict:
    if args.stage == "rtl":
        return source_recipe(args)
    return sim_recipe(args)


def verify(args: argparse.Namespace) -> tuple[bool, str, str]:
    marker = cache_path(args)
    try:
        if args.stage == "sim":
            rtl_args = argparse.Namespace(**{**vars(args), "stage": "rtl"})
            if not verify(rtl_args)[0]:
                return False, "unqualified-rtl", "unavailable"
        expected = key_for(recipe_for(args))
        if not marker.is_file():
            return False, "missing-manifest", expected
        saved = json.loads(marker.read_text(encoding="utf-8"))
        if not isinstance(saved, dict):
            return False, "invalid-manifest", expected
        if saved.get("schema") != SCHEMA or saved.get("key") != expected:
            return False, "recipe-changed", expected
        actual = {str(path.resolve()): digest(path) for path in artifacts(args)}
        if saved.get("artifacts") != actual:
            return False, "artifact-changed", expected
        return True, "qualified", expected
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        return False, f"invalid:{type(exc).__name__}", "unavailable"


def stamp(args: argparse.Namespace) -> str:
    # Model qualification is only valid atop a separately qualified RTL tree.
    if args.stage == "sim":
        rtl_args = argparse.Namespace(**{**vars(args), "stage": "rtl"})
        if not verify(rtl_args)[0]:
            raise ValueError("cannot qualify a simulator over unqualified RTL")
    recipe = recipe_for(args)
    key = key_for(recipe)
    marker = cache_path(args)
    value = {
        "schema": SCHEMA,
        "key": key,
        "artifacts": {str(path.resolve()): digest(path) for path in artifacts(args)},
    }
    marker.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", dir=marker.parent,
        prefix=f"{marker.name}.tmp-", delete=False,
    ) as stream:
        temp = Path(stream.name)
        json.dump(value, stream, sort_keys=True, indent=2)
        stream.write("\n")
    try:
        os.replace(temp, marker)
    finally:
        temp.unlink(missing_ok=True)
    return key


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "stamp", "key"))
    parser.add_argument("--stage", choices=("rtl", "sim"), required=True)
    parser.add_argument("--rtl-dir", required=True)
    parser.add_argument("--obj-dir", default="")
    parser.add_argument("--top", default="")
    parser.add_argument("--elaborate-main", required=True)
    parser.add_argument("--verilator", default="verilator")
    parser.add_argument("--verilator-model-opt", default="-O3")
    parser.add_argument("--verilator-threads", default="1")
    parser.add_argument("--verilator-make-opt", default="")
    parser.add_argument("--sim-cxxflags", default="")
    parser.add_argument("--runner", default="sim/opensbi_boot_main.cpp")
    parser.add_argument("--cxx", default="g++")
    args = parser.parse_args()

    if args.action == "key":
        try:
            print(key_for(recipe_for(args)))
        except (OSError, ValueError, subprocess.SubprocessError) as exc:
            parser.error(str(exc))
        return 0

    if args.action == "check":
        hit, reason, key = verify(args)
        status = "HIT" if hit else "MISS"
        print(f"AETHERCORE_SIM_CACHE_{status} stage={args.stage} key={key} reason={reason}")
        return 0 if hit else 1
    try:
        key = stamp(args)
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        parser.error(str(exc))
    print(f"AETHERCORE_SIM_CACHE_STAMP stage={args.stage} key={key}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
