#!/usr/bin/env python3
"""Inject an EXPERIMENTAL host-memory callback in pinned GSIM emitter.

Only code generation is patched; the PA32 Chisel and SystemVerilog remain
unchanged. Enforce exact upstream anchor matching before touching a file.
"""
from pathlib import Path
import sys

HEADER = '  fprintf(header, "uint64_t cycles;\\n");'
GENERATE = 'int graph::genActivate() {'
EVAL = '''      genSuperEval(super, flagName, indent);
      indent = genNodeStepEnd(super, indent);'''

BRIDGE_SUPER = '''static bool pa32HostBridgeRequestSuper(const SuperNode* super) {
  for (const Node* node : super->member) {
    const std::string& name = node->name;
    if (name == "io$$imemValid" || name == "io$$imemAddr" ||
        name == "io$$imemBytes" || name == "io$$memValid" ||
        name == "io$$memAddr" || name == "io$$ptwValid" ||
        name == "io$$ptwAddr") return true;
  }
  return false;
}

'''

def patch_source(source: str) -> str:
    for anchor in (HEADER, GENERATE, EVAL):
        if source.count(anchor) != 1:
            raise ValueError("pinned GSIM source has drifted: " + anchor[:80])
    if "pa32HostBridgeRequestSuper" in source:
        raise ValueError("host bridge already patched")
    source = source.replace(
        HEADER,
        HEADER + '\n  fprintf(header, "void (*pa32HostBridge)(void*) = nullptr;\\nvoid* pa32HostContext = nullptr;\\n");',
        1,
    )
    source = source.replace(GENERATE, BRIDGE_SUPER + GENERATE, 1)
    source = source.replace(
        EVAL,
        '''      genSuperEval(super, flagName, indent);
      // Feed external RAM between dependent supernode evaluations, not ticks.
      if (pa32HostBridgeRequestSuper(super))
        emitBodyLock(indent, "if (pa32HostBridge) pa32HostBridge(pa32HostContext);\\n");
      indent = genNodeStepEnd(super, indent);''',
        1,
    )
    return source

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: rv64_pa32_gsim_host_bridge_patch.py GSIM/src/cppEmitter.cpp")
    path = Path(sys.argv[1])
    path.write_text(patch_source(path.read_text()))
    print("AETHERCORE_PA32_GSIM_HOST_BRIDGE_COMPILER_PATCH_APPLIED")
