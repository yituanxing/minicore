#!/usr/bin/env python3
"""Conservative scoped GSIM frontend patch, NOT a CPU/RTL source change.

The pinned GSIM fails in dupTreeWithBits on opType 19 (OP_ASUINT).
A slice of asUInt/asSInt is bit-identical to a slice of the complete cast.
Keep the original expression and add a bits node outside it, rather than
trying to push the slice through it. Any other unsupported operator remains
a fail-closed compiler error.
"""
import pathlib
import sys

FUNCTION = "ExpTree* dupTreeWithBits(ExpTree* tree, int _hi, int _lo) {"
ANCHOR = "        case OP_DSHR: case OP_DSHL: case OP_SUB: {"
INSERT = """        case OP_ASUINT: case OP_ASSINT: {
          // Preserve the entire cast before taking a bit slice. Both
          // operations reinterpret the same width bits, so this is exact.
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

def patch_source(original):
    if original.count(FUNCTION) != 1:
        raise ValueError("unexpected pinned GSIM dupTreeWithBits function")
    pre, body = original.split(FUNCTION, 1)
    if body.count(ANCHOR) != 1:
        raise ValueError("upstream source has changed the expected expression-splitting switch")
    if "case OP_ASUINT: case OP_ASSINT: {" in body:
        raise ValueError("upstream now handles the casts; remove the local patch")
    return pre + FUNCTION + body.replace(ANCHOR, INSERT + ANCHOR, 1)

def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: rv64_pa32_gsim_cast_patch.py PATH/TO/gsim/src/splitNodes.cpp")
    path = pathlib.Path(sys.argv[1])
    old = path.read_text()
    new = patch_source(old)
    path.write_text(new)
    print("AETHERCORE_PA32_GSIM_PATCH_APPLIED ops=OP_ASUINT,OP_ASSINT")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
