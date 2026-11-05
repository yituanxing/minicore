#!/usr/bin/env python3
"""Strict, scoped patches for pinned GSIM rev 45a73d6 (Product host RAM).

Based on the proven RV64 PA32 host-bridge patches: allow cast bit-slicing and
invoke the Product host callback between dependent supernode evaluations.
No Chisel/RTL optimization or behavioral bypass is introduced here.
"""

from pathlib import Path
import sys


def patch_cast(source: str) -> str:
    anchor = "        case OP_DSHR: case OP_DSHL: case OP_SUB: {"
    function = "ExpTree* dupTreeWithBits(ExpTree* tree, int _hi, int _lo) {"
    if source.count(function) != 1 or source.count(anchor) != 1:
        raise ValueError("pinned GSIM splitNodes.cpp anchors changed")
    if "case OP_ASUINT: case OP_ASSINT: {" in source:
        raise ValueError("GSIM upstream now handles ASUINT/ASSINT; remove patch")
    block = """        case OP_ASUINT: case OP_ASSINT: {
          // A cast does not alter the underlying bits. Slice its full result.
          ENode* bits = new ENode(OP_BITS);
          bits->addVal(hi);
          bits->addVal(lo);
          bits->width = hi - lo + 1;
          bits->addChild(top);
          if (parent) parent->child[idx] = bits;
          else rvalue = bits;
          break;
        }
"""
    return source.replace(anchor, block + anchor, 1)


def patch_bridge(source: str) -> str:
    header = '  fprintf(header, "uint64_t cycles;\\n");'
    generate = "int graph::genActivate() {"
    evaluation = """      genSuperEval(super, flagName, indent);
      indent = genNodeStepEnd(super, indent);"""
    for anchor in (header, generate, evaluation):
        if source.count(anchor) != 1:
            raise ValueError("pinned GSIM cppEmitter.cpp anchor changed: " + anchor[:80])
    if "productHostBridgeRequestSuper" in source:
        raise ValueError("Product host bridge already patched")
    selector = """static bool productHostBridgeRequestSuper(const SuperNode* super) {
  for (const Node* node : super->member) {
    const std::string& name = node->name;
    if (name == "io$$imemValid" || name == "io$$imemAddr" ||
        name == "io$$imemBytes" || name == "io$$memValid" ||
        name == "io$$memAddr" || name == "io$$ptwValid" ||
        name == "io$$ptwAddr") return true;
  }
  return false;
}

"""
    source = source.replace(
        header,
        header + '\n  fprintf(header, "void (*productHostBridge)(void*) = nullptr;\\nvoid* productHostContext = nullptr;\\n");',
        1,
    )
    source = source.replace(generate, selector + generate, 1)
    source = source.replace(
        evaluation,
        """      genSuperEval(super, flagName, indent);
      // Same-tick external memory handshake, after each request producer.
      if (productHostBridgeRequestSuper(super))
        emitBodyLock(indent, "if (productHostBridge) productHostBridge(productHostContext);\\n");
      indent = genNodeStepEnd(super, indent);""",
        1,
    )
    return source


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: product_gsim_frontend_patch.py GSIM/src/splitNodes.cpp GSIM/src/cppEmitter.cpp")
    cast_file, bridge_file = (Path(arg) for arg in sys.argv[1:])
    updated_cast = patch_cast(cast_file.read_text())
    updated_bridge = patch_bridge(bridge_file.read_text())
    cast_file.write_text(updated_cast)
    bridge_file.write_text(updated_bridge)
    print("AETHERCORE_PRODUCT_GSIM_SCOPED_PATCH_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
