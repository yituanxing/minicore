#!/usr/bin/env python3
"""Read-only ELF/link_map recovery from verified AETSDCP1 Product RTL DDR.

Inspect only the user page tables of the sole PID1 and report dynamic linker
runtime names and module load bases.  No guest state is modified.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import struct
import sys

probe_path = Path(__file__).with_name("product_sd1_generator_progress.py")
if not probe_path.is_file():
    # Backward-compatible name for an extracted scratch-only forensic probe.
    probe_path = Path(__file__).with_name("sd1-generator-progress.py")
spec = importlib.util.spec_from_file_location("sd1probe", probe_path)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def u64(buf, offset=0):
    return struct.unpack_from("<Q", buf, offset)[0]


def read_elf(mem, root, addr):
    h = mem.virt(root, addr, 64)
    if h[:4] != b"\x7fELF" or h[4:6] != b"\x02\x01" or h[18:20] != b"\xf3\x00":
        return None
    e_type = struct.unpack_from("<H", h, 16)[0]
    phoff = u64(h, 32)
    entsize, phnum = struct.unpack_from("<HH", h, 54)
    if not (1 <= phnum <= 128 and 56 <= entsize <= 128 and phoff < 4096):
        return None
    phdrs = []
    for i in range(phnum):
        ph = mem.virt(root, addr+phoff+i*entsize, 56)
        t, flags, off, vaddr, paddr, filesz, memsz, align = struct.unpack("<IIQQQQQQ", ph)
        phdrs.append({"type": t, "flags": flags, "offset": off, "vaddr": vaddr, "filesz": filesz, "memsz": memsz})
    dyn = next((p for p in phdrs if p["type"] == 2), None)
    dbg = None
    if dyn:
        try:
            for i in range(min(dyn["memsz"]//16, 1024)):
                tag,val = struct.unpack("<QQ", mem.virt(root,addr+dyn["vaddr"]+i*16,16))
                if tag == 0: break
                if tag == 21: dbg = val
        except ValueError: pass
    return {"base": hex(addr), "e_type": e_type, "entry": hex(u64(h,24)),
            "dynamic_debug_ptr": None if dbg is None else hex(dbg),
            "segments": [{"vaddr": hex(p["vaddr"]),"memsz":p["memsz"],"flags":p["flags"]}
                         for p in phdrs if p["type"] == 1], "debug_ptr":dbg}


def linkmap(mem, root, debug_ptr):
    if not debug_ptr: return []
    try:
        dbg = mem.virt(root,debug_ptr,40)
        cur = u64(dbg,8)
    except ValueError: return []
    out=[]
    seen=set()
    for i in range(80):
        if not cur or cur in seen: break
        seen.add(cur)
        try:
            buf=mem.virt(root,cur,40)
            base,name_ptr,ld,next_ptr = (u64(buf,k) for k in (0,8,16,24))
            name=""
            if name_ptr:
                data=mem.virt(root,name_ptr,1024)
                name=data.split(b"\0",1)[0].decode("utf-8","replace")
            out.append({"module_base":hex(base),"name":name,"ld":hex(ld),"linkmap":hex(cur)})
            cur=next_ptr
        except ValueError as e:
            out.append({"error":str(e),"linkmap":hex(cur)})
            break
    return out


def symbols_for_module(mem, root, base):
    """Recover usable dynsym names from PT_DYNAMIC, without a rootfs file."""
    h = mem.virt(root, base, 64)
    phoff = u64(h, 32)
    entsize, phnum = struct.unpack_from("<HH", h, 54)
    dyn = None
    for i in range(phnum):
        ph = mem.virt(root, base + phoff + i*entsize, 56)
        typ, flags, off, vaddr, paddr, filesz, memsz, align = struct.unpack("<IIQQQQQQ", ph)
        if typ == 2: dyn = (base+vaddr, memsz)
    if not dyn: return []
    tags={}
    for i in range(min(dyn[1]//16,2048)):
        tag,val=struct.unpack("<QQ",mem.virt(root,dyn[0]+i*16,16))
        if tag==0: break
        tags.setdefault(tag,val)
    if 5 not in tags or 6 not in tags or tags.get(11,24)!=24: return []
    resolve=lambda p: p if p >= 0x1000000000 else base+p
    symtab=resolve(tags[6]); strtab=resolve(tags[5]); strsz=tags.get(10,1_000_000)
    count=0
    if 4 in tags: # DT_HASH (SysV)
        count=struct.unpack("<I",mem.virt(root,resolve(tags[4])+4,4))[0]
    elif 0x6ffffef5 in tags: # DT_GNU_HASH
        gh=resolve(tags[0x6ffffef5]); hdr=mem.virt(root,gh,16)
        nbuckets,symoffset,bloom_size,bloom_shift=struct.unpack("<IIII",hdr)
        if not (0<nbuckets<65536 and bloom_size<65536): return []
        buckets_start=gh+16+bloom_size*8
        highest=symoffset
        for b in range(nbuckets):
            index=struct.unpack("<I",mem.virt(root,buckets_start+b*4,4))[0]
            if not index: continue
            if index<symoffset: continue
            # Every bucket's chain is terminated with its low bit.
            for c in range(100_000):
                v=struct.unpack("<I",mem.virt(root,buckets_start+nbuckets*4+(index+c-symoffset)*4,4))[0]
                highest=max(highest,index+c)
                if v&1: break
            else: return []
        count=highest+1
    if count > 100_000 or not count: return []
    symbols=[]
    for i in range(count):
        raw=mem.virt(root,symtab+i*24,24)
        st_name,st_info,st_other,st_shndx,st_value,st_size=struct.unpack("<IBBHQQ",raw)
        if not st_value or st_name>=strsz or st_info&15 not in (2,10): continue
        name=mem.virt(root,strtab+st_name,256).split(b"\0",1)[0].decode("utf-8","replace")
        if name: symbols.append({"offset":st_value,"size":st_size,"name":name})
    return sorted(symbols,key=lambda x:x["offset"])


def sample_modules(mem,root,elf,modules,progress):
    out=[]
    by_base={int(x["base"],16):x for x in elf}
    symcache={}
    for pcstr in progress["cpu_observer"]["last_16_retired_pc"]:
        pc=int(pcstr,16)
        hits=[]
        for module in modules:
            base=int(module["module_base"],16)
            h=by_base.get(base)
            if not h: continue
            for segment in h["segments"]:
                vaddr=int(segment["vaddr"],16)
                if segment["flags"]&1 and base+vaddr<=pc<base+vaddr+segment["memsz"]:
                    name=module["name"] or "<systemd-main-PIE>"
                    offset=pc-base
                    entry={"pc":pcstr,"module":name,"base":hex(base),"offset":hex(offset)}
                    if base not in symcache:
                        try:symcache[base]=symbols_for_module(mem,root,base)
                        except (ValueError,struct.error):symcache[base]=[]
                    symbols=symcache[base]
                    near=[s for s in symbols if s["offset"]<=offset]
                    if near:
                        symbol=near[-1]
                        if symbol["size"] and offset < symbol["offset"]+symbol["size"]:
                            entry["symbol"]=symbol["name"]
                            entry["symbol_delta"]=hex(offset-symbol["offset"])
                        else:
                            entry["nearest_preceding_export"]=symbol["name"]
                            entry["export_distance"]=hex(offset-symbol["offset"])
                    hits.append(entry)
        if not hits: hits.append({"pc":pcstr,"module":None})
        out.extend(hits)
    return out


def scan(mem, root):
    """Walk Sv39 user page table and inspect mapped 4KiB page starts."""
    bases=[]
    leaves=0
    elf=[]
    def descend(table,level,prefix):
        nonlocal leaves
        data=mem.phys(table,4096)
        for index in range(512):
            pte=u64(data,index*8)
            if not (pte&1): continue
            vaddr=prefix+(index << (12+9*level))
            phys=(pte>>10)<<12
            if pte&0xe:
                pages=1 << (9*level)
                leaves += pages
                if level==0:
                    try:
                        start=mem.phys(phys,4)
                    except ValueError:
                        continue
                    if start==b"\x7fELF":
                        try:
                            e=read_elf(mem,root,vaddr)
                            if e: elf.append(e)
                        except ValueError: pass
                else:
                    # Large mappings: inspect their 4KiB boundaries, but cap
                    # a single entry to avoid sweeping unsupported 1GiB RAM.
                    for offset in range(min(pages,512)):
                        va=vaddr+offset*4096
                        try:
                            if mem.phys(phys+offset*4096,4)==b"\x7fELF":
                                e=read_elf(mem,root,va)
                                if e: elf.append(e)
                        except ValueError: pass
            elif level>0:
                try:descend(phys,level-1,vaddr)
                except ValueError: pass
    # Sv39 lower canonical user half: top-level slots 0..255.
    root_phys=mem.direct_phys(root)
    top=mem.phys(root_phys,4096)
    for i in range(256):
        pte=u64(top,i*8)
        if pte & 1:
            phys=(pte>>10)<<12
            if pte&0xe:
                continue
            descend(phys,1,i<<30)
    return leaves,elf


def main(checkpoint, system_map):
    # Kernel offsets are inherited from the adjacent frozen-image probe.
    # Check the exact System.map even though DSO symbols come from guest DDR.
    probe.symbols(system_map)
    pins=probe.read_manifest(checkpoint / ".migration-ready")
    if probe.sha256(checkpoint / "persistent.pre.host") != pins["snapshot_host_sha256"]:
        raise ValueError("Host SHA mismatch")
    if probe.sha256(checkpoint / "persistent.pre.rtl") != pins["snapshot_rtl_sha256"]:
        raise ValueError("RTL SHA mismatch")
    if probe.sha256(checkpoint / "card-progress.json") != pins["card_progress_sha256"]:
        raise ValueError("card-progress SHA mismatch")
    with (checkpoint / "persistent.pre.host").open("rb") as f:
        mem=probe.DDR(f)
        t=next((raw for pid,addr,raw in mem.tasks() if pid==1),None)
        if t is None: raise ValueError("PID1 absent")
        root=mem.qdirect(u64(t,probe.MM_OFF)+probe.MM_PGD_OFF)
        leaves,elf=scan(mem,root)
        names=[]
        for x in elf:
            names=linkmap(mem,root,x["debug_ptr"])
            if names: break
        progress=json.loads((checkpoint/"card-progress.json").read_text())
        return {"checkpoint":str(checkpoint),"boot_cycles":pins["boot_cycles"],
                "linux_image_sha256":pins["linux_image_sha256"],
                "verified_system_map_sha256":probe.MAP_SHA,
                "verified_host_sha256":pins["snapshot_host_sha256"],
                "verified_rtl_sha256":pins["snapshot_rtl_sha256"],
                "verified_progress_sha256":pins["card_progress_sha256"],
                "pgd":hex(root),"mapped_user_pages":leaves,
                "elf_headers": [{k:v for k,v in e.items() if k!="debug_ptr"} for e in elf],
                "link_map":names,
                "cpu_user_pc_modules":sample_modules(mem,root,elf,names,progress)}


if __name__=="__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path, help="Extracted AETSDCP1 snapshot directory")
    parser.add_argument("system_map", type=Path, help="System.map pinned to exact frozen SD1 Linux Image")
    args = parser.parse_args()
    print(json.dumps(main(args.checkpoint, args.system_map), indent=2))
