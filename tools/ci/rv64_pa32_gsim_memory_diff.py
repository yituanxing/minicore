#!/usr/bin/env python3
"""Cross-engine RV64 ordinary store / LR-SC / AMO result gate."""
import pathlib
import sys
from rv64_pa32_gsim_bare_diff import parse, FIELDS

EXPECTED = (
    ("0x8000000c","0x0023a023"),  # SW x2,0(x7)
    ("0x80000010","0x0003a183"),  # LW x3,0(x7)
    ("0x80000018","0x1003a2af"),  # LR.W x5,(x7)
    ("0x8000001c","0x1843a32f"),  # SC.W x6,x4,(x7)
    ("0x80000024","0x0023a4af"),  # AMOADD.W x9,x2,(x7)
    ("0x80000028","0x0003a503"),  # LW x10,0(x7)
)
def compare(gs, vl):
    gf, gcommits, _, _ = parse(gs,"GSIM")
    vf, vcommits, _, _ = parse(vl,"VERILATOR")
    for key in FIELDS:
        if gf.get(key) != vf.get(key):
            raise ValueError(f"{key} mismatch: GSIM={gf.get(key)} Verilator={vf.get(key)}")
    if gcommits != vcommits:
        raise ValueError(f"first retirement divergence: GSIM={gcommits} Verilator={vcommits}")
    if int(gf["exceptions"]) != 0 or int(gf["retires"]) < 15:
        raise ValueError("atomic program did not retire without exceptions")
    for pc, instruction in EXPECTED:
        if not any(row[1] == pc and row[2] == instruction and
                   row[3] == "0" for row in gcommits):
            raise ValueError(f"missing real memory instruction at {pc} ({instruction})")
    for engine, path in (("GSIM",gs),("VERILATOR",vl)):
        marker=f"AETHERCORE_PA32_{engine}_MEMORY_PASS final_word=85"
        if marker not in pathlib.Path(path).read_text().splitlines():
            raise ValueError(f"{engine} did not assert expected 42->43->85 RAM result")
    print(f"AETHERCORE_PA32_GSIM_VERILATOR_MEMORY_DIFF_PASS "
          f"cycles={gf['guest_cycles']} retires={gf['retires']} "
          f"ram_digest={gf['ram_digest']} commit_digest={gf['commit_digest']}")

if __name__=="__main__":
    if len(sys.argv)!=3:
        raise SystemExit("usage: rv64_pa32_gsim_memory_diff.py GSIM.log VERILATOR.log")
    try: compare(sys.argv[1],sys.argv[2])
    except (ValueError,OSError) as error:
        print(f"AETHERCORE_PA32_GSIM_VERILATOR_MEMORY_DIFF_FAIL {error}",
              file=sys.stderr)
        sys.exit(1)
