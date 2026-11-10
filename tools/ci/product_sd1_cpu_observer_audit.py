#!/usr/bin/env python3
"""Read-only authenticated AETSDCP1 CPU/UART audit (no RTL cycles)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from product_sd1_relay_state import (MAGIC, VERSION, FNV_OFFSET, FNV_PRIME,
    MAX_UART, read_exact, read_u64, read_string)

IDENTITY_KEYS=("model","firmware","dtb","sd_image","linux_image","rootfs_result")


def inspect(stream):
    if read_exact(stream,8)!=MAGIC or read_u64(stream)!=VERSION:
        raise ValueError("wrong AETSDCP1 schema")
    ids=[read_string(stream,128).decode("ascii") for _ in range(6)]
    if not all(re.fullmatch(r"[0-9a-f]{64}",value) for value in ids):
        raise ValueError("invalid host identity")
    read_string(stream,128);read_string(stream,4096)
    read_u64(stream)
    time=read_u64(stream)
    read_u64(stream)
    o=[read_u64(stream) for _ in range(15)]
    uart=read_string(stream,MAX_UART)
    if any(read_u64(stream)>1 for _ in range(3)):
        raise ValueError("invalid observer flag")
    read_u64(stream);read_u64(stream)
    if any(read_u64(stream)>1 for _ in range(3)):
        raise ValueError("invalid UART input flag")
    progress=[read_u64(stream) for _ in range(6)]
    ring=[read_u64(stream) for _ in range(16)]
    count,index=read_u64(stream),read_u64(stream)
    if count>16 or index>=16 or (count==0 and index!=0):
        raise ValueError("invalid recent PC ring")
    fnv=FNV_OFFSET
    for ch in uart: fnv=((fnv^ch)*FNV_PRIME)&((1<<64)-1)
    if time!=o[0] or o[13]!=fnv or progress[5]>time:
        raise ValueError("observer cycle/UART fingerprint mismatch")
    low=uart.lower()
    return dict(
      identities=dict(zip(IDENTITY_KEYS,ids)),cycles=o[0],retired=o[1],
      exceptions=o[5],interrupts=o[6],timer_interrupts=o[7],
      external_interrupts=o[8],last_exception_cause=o[9],
      last_exception_pc=f"0x{o[11]:x}",
      last_exception_is_user_ecall=(o[5]>0 and o[9]==8),
      last_16_retired_pc=[f"0x{ring[(index-count+k)%16]:x}" for k in range(count)],
      uart_bytes=len(uart),uart_fnv=f"{fnv:016x}",
      last_uart_cycle=progress[5],
      last_uart_age_cycles=time-progress[5] if progress[5] else None,
      root_ext4=b"vfs: mounted root (ext4 filesystem)" in low,
      kernel_init_exec_marker=b"run /sbin/init as init process" in low,
      login_seen=b"login:" in low)


def sha_file(path):
    digest=hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda:stream.read(4*1024*1024),b""):
            digest.update(chunk)
    return digest.hexdigest()


def stage(path,expected_cycles):
    pins={}
    for line in (path/".polling-ready").read_text().splitlines():
        k,sep,v=line.partition("=")
        if not sep or not k or k in pins:raise ValueError("bad stage manifest")
        pins[k]=v
    if (pins.get("qualification")!="PRODUCT_SD1_SPI_POLLING_WARM_NOT_DEBIAN_PASS"
        or pins.get("schema")!=MAGIC.decode()
        or pins.get("boot_cycles")!=str(expected_cycles)):
        raise ValueError("wrong polling firmware checkpoint/cycles")
    host=path/"persistent.pre.host"
    if host.stat().st_size<=268435456:raise ValueError("full DDR host state missing")
    for kind in ("host","rtl"):
        if sha_file(path/("persistent.pre."+kind))!=pins.get("snapshot_"+kind+"_sha256"):
            raise ValueError("snapshot "+kind+" SHA mismatch")
    if sha_file(path/"card-progress.json")!=pins.get("card_progress_sha256"):
        raise ValueError("card-progress SHA mismatch")
    with host.open("rb") as stream: value=inspect(stream)
    if value["cycles"]!=expected_cycles:raise ValueError("cycle mismatch")
    for key in IDENTITY_KEYS:
        if value["identities"][key]!=pins.get(key+"_sha256"):
            raise ValueError("immutable "+key+" identity mismatch")
    return value


def compare(before,after):
    if before["identities"]!=after["identities"] or before["cycles"]>=after["cycles"]:
        raise ValueError("foreign checkpoint/cycle regression")
    counters=("retired","exceptions","interrupts","timer_interrupts",
              "external_interrupts","uart_bytes")
    if any(after[k]<before[k] for k in counters):
        raise ValueError("CPU/UART counter regressed")
    result=dict(qualification="CPU_OBSERVER_ONLY_NOT_DEBIAN_LOGIN_PASS",
        from_cycles=before["cycles"],to_cycles=after["cycles"],
        last_exception_cause=after["last_exception_cause"],
        last_exception_pc=after["last_exception_pc"],
        last_exception_is_user_ecall=after["last_exception_is_user_ecall"],
        last_16_retired_pc=after["last_16_retired_pc"],
        last_uart_cycle=after["last_uart_cycle"],
        last_uart_age_cycles=after["last_uart_age_cycles"],
        root_ext4=after["root_ext4"],
        kernel_init_exec_marker=after["kernel_init_exec_marker"],
        login_seen=after["login_seen"],
        caveat="U-mode ecall does not identify PID1; UART silence does not prove deadlock.")
    for k in counters:result[k+"_delta"]=after[k]-before[k]
    result["retirement_during_uart_silence"]=(
        result["retired_delta"]>0 and result["uart_bytes_delta"]==0)
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--before",type=Path,required=True)
    p.add_argument("--before-cycles",type=int,required=True)
    p.add_argument("--after",type=Path,required=True)
    p.add_argument("--after-cycles",type=int,required=True)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    report=compare(stage(a.before,a.before_cycles),stage(a.after,a.after_cycles))
    if a.output.exists():p.error("refuse to overwrite prior evidence")
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(report,indent=2,sort_keys=True)+"\n")
    print("PRODUCT_SD1_CPU_OBSERVER_AUDIT: "+json.dumps(report,sort_keys=True))


if __name__=="__main__":main()
