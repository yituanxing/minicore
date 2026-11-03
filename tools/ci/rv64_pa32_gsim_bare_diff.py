#!/usr/bin/env python3
"""Fail-closed PA32 GSIM/Verilator real-instruction differential.

Comparing terminal state is necessary but not enough: compare the first
16 retirement PC/inst/exception tuples and all 20k-cycle commit digests.
Never accept the zero-instruction illegal-instruction loop as a PASS.
"""
import pathlib
import re
import sys

FIELDS = ("guest_cycles", "retires", "exceptions", "interrupts",
          "mtime", "last_pc", "commit_digest", "ram_digest",
          "uart_bytes", "uart_digest", "opensbi_banner", "linux_banner",
          "milestone_cycle", "stip", "seip")
def parse(path, engine):
    lines = pathlib.Path(path).read_text().splitlines()
    prefix = f"AETHERCORE_PA32_{engine}_PREFIX "
    finish = [x for x in lines if x.startswith(prefix)]
    if len(finish) != 1:
        raise ValueError(f"{engine}: expected exactly one prefix summary, got {len(finish)}")
    summary = dict(re.findall(r"([\w-]+)=([^\s]+)", finish[0]))
    missing = [field for field in FIELDS if field not in summary]
    if missing:
        raise ValueError(f"{engine}: missing summary fields {missing}")
    commits = []
    retire_pattern = f"AETHERCORE_PA32_{engine}_COMMIT "
    for line in lines:
        if line.startswith(retire_pattern):
            fields = dict(re.findall(r"([\w-]+)=([^\s]+)",line))
            commits.append(tuple(fields.get(k) for k in
                                 ("index","pc","inst","exception","cause")))
    if not commits:
        raise ValueError(f"{engine}: no committed instructions")
    checkpoint_prefix = f"AETHERCORE_PA32_{engine}_CHECKPOINT "
    checkpoints = []
    for line in lines:
        if line.startswith(checkpoint_prefix):
            fields = dict(re.findall(r"([\w-]+)=([^\s]+)", line))
            checkpoints.append(tuple(fields.get(k) for k in
                ("cycle", "retires", "exceptions", "interrupts", "mtime",
                 "last_pc", "commit_digest")))
    linux_pass = [x for x in lines if
                  x.startswith(f"AETHERCORE_PA32_{engine}_LINUX_MILESTONE_PASS ")]
    return summary, commits, checkpoints, linux_pass

def compare(gsfile, vlfile, strict_bare=True, require_linux=False):
    g, gc, gcheck, glinux = parse(gsfile, "GSIM")
    v, vc, vcheck, vlinux = parse(vlfile, "VERILATOR")
    if not strict_bare:
        if not gcheck or not vcheck:
            raise ValueError("firmware differential requires periodic checkpoints")
        if gcheck != vcheck:
            for i, (a, b) in enumerate(zip(gcheck, vcheck)):
                if a != b:
                    raise ValueError(f"FIRST_CHECKPOINT_DIVERGENCE index={i} "
                                     f"cycle={a[0]} GSIM={a} Verilator={b}")
            raise ValueError(f"checkpoint count mismatch GSIM={len(gcheck)} "
                             f"Verilator={len(vcheck)}")
    for key in FIELDS:
        if g.get(key) != v.get(key):
            raise ValueError(f"field {key}: GSIM={g.get(key)} Verilator={v.get(key)}")
    if gc != vc:
        for i, (a,b) in enumerate(zip(gc,vc)):
            if a != b:
                raise ValueError(f"retirement mismatch index={i}: GSIM={a} Verilator={b}")
        raise ValueError(f"retirement count mismatch: GSIM={len(gc)} Verilator={len(vc)}")
    if require_linux:
        if len(glinux) != 1 or len(vlinux) != 1:
            raise ValueError("real Linux requires one PID1 milestone PASS per engine")
        for key in ("opensbi_banner", "linux_banner"):
            if g.get(key) != "1":
                raise ValueError(f"real Linux requires {key}=1")
        if int(g.get("milestone_cycle", "0")) == 0:
            raise ValueError("real Linux PID1 UART marker missing")
        if int(g.get("stip", "0")) == 0 or int(g.get("seip", "0")) == 0:
            raise ValueError("real Linux requires STIP and SEIP interrupts")
        if int(g.get("uart_bytes", "0")) < 64:
            raise ValueError("real Linux UART output too short")
        print(f"AETHERCORE_PA32_GSIM_VERILATOR_LINUX_PID1_PASS "
              f"cycles={g['guest_cycles']} retires={g['retires']} "
              f"stip={g['stip']} seip={g['seip']} "
              f"uart_bytes={g['uart_bytes']} uart_digest={g['uart_digest']} "
              f"ram_digest={g['ram_digest']}")
        return
    if not strict_bare:
        if int(g["retires"]) < 100:
            raise ValueError("OpenSBI firmware prefix retired too few instructions")
        print(f"AETHERCORE_PA32_GSIM_VERILATOR_FIRMWARE_DIFF_PASS "
              f"cycles={g['guest_cycles']} retires={g['retires']} "
              f"exceptions={g['exceptions']} mtime={g['mtime']} "
              f"digest={g['commit_digest']} ram_digest={g['ram_digest']} "
              f"checkpoints={len(gcheck)}")
        return
    if int(g["exceptions"]) != 0 or int(g["retires"]) < 4:
        raise ValueError("bare RV64I test must retire real instructions without traps")
    for expected, pc, instruction in (
        (4, "0x80000000", "0x00100093"),
        (5, "0x80000004", "0x00208113"),
        (6, "0x80000008", "0x00310193"),
    ):
        if not any(int(x[0]) == expected and x[1] == pc and x[2] == instruction
                   for x in gc):
            raise ValueError(f"real RV64I sequence mismatch at {pc}")
    print(f"AETHERCORE_PA32_GSIM_VERILATOR_BARE_DIFF_PASS "
          f"cycles={g['guest_cycles']} retires={g['retires']} "
          f"exceptions={g['exceptions']} mtime={g['mtime']} "
          f"digest={g['commit_digest']}")

if __name__=="__main__":
    if len(sys.argv) not in (3,4,5) or (len(sys.argv)>=4 and sys.argv[3]!="--firmware") or (len(sys.argv)==5 and sys.argv[4]!="--linux"):
        raise SystemExit("usage: rv64_pa32_gsim_bare_diff.py GSIM.log VERILATOR.log [--firmware [--linux]]")
    try:
        compare(sys.argv[1],sys.argv[2],strict_bare=(len(sys.argv)==3),
                require_linux=(len(sys.argv)==5))
    except (ValueError, OSError) as error:
        print(f"AETHERCORE_PA32_GSIM_VERILATOR_BARE_DIFF_FAIL {error}",file=sys.stderr)
        sys.exit(1)
