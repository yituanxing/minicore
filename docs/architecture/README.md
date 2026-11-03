# RV64GC FPGA Linux product architecture

Source of truth for **proposed** product G0; freeze requires design decisions, and the product RTL is not yet implemented.

- [Detailed G0 data flow, owners, and same-cycle event rules](RV64GC_G0_DESIGN_AND_OWNERSHIP.md)
- [Machine-readable owners/identities/interfaces/scenarios](rv64gc_g0_contract.json)
- [G1 migration choices: what to change and close](RV64GC_G1_MIGRATION_DECISION.md)
- [File-level migration map](rv64gc_g1_migration_map.json)
- [Pure-Python G1 rename/undo reference model](../../tests_py/product_g1_rename_reference.py)
- [Pre-G0 architecture gap review](../RV64GC_FPGA_PRODUCT_ARCHITECTURE_GAP_2026-11-03.md)
- [Executable architecture-contract checks](../../tests_py/test_product_g0_contract.py)

The currently qualified v0/v2 implementation and its Linux evidence remain in `docs/AETHERSOC_V0_FREEZE.md`, `docs/ROADMAP.md` and their original CI run artifacts. The new G0 reviews **do not** qualify the final dual-width RV64GC/LP64D CPU.

Execution:

```sh
python3 -m unittest tests_py.test_product_g0_contract tests_py.test_product_g1_migration_contract tests_py.test_product_g1_rename_reference -v
```

This checks consistency and today's existing source anchors, not FPGA timing, Chisel elaboration of a product core or dynamic RTL correctness. Each later G1 implementation PR must add its own cycle-accurate directed tests, retirement-differential oracle and physical resource budget before changing the production line.
