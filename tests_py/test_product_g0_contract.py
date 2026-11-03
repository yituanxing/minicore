"""Proposed RV64GC product G0 contracts, *not* RTL verification.

A deliberately small executable specification for dispatch/retire semantics.
This tests the proposed ownership map and scenarios, and pins the legacy
AetherCore v2 source anchors; it does not elaborate or prove a new CPU.
"""
import json
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "docs" / "architecture" / "rv64gc_g0_contract.json"
REVIEW = ROOT / "docs" / "architecture" / "RV64GC_G0_DESIGN_AND_OWNERSHIP.md"
SRC = ROOT / "src" / "main" / "scala" / "aethercore"
MAIN_SHA = "7ff35c6acdc3068754292bba814e4ec52ba4ec50"


def dispatch_accepted(case):
    """Proposed all-or-nothing group allocation; independent of source RTL."""
    n = case["requested"]
    if case["redirect"] or n not in (1, 2):
        return 0
    return n if all(
        case["resources"][name] >= amount
        for name, amount in case["needed"].items()
    ) else 0


def retire_prefix(entries, width=2):
    """Oldest contiguous commit prefix. Fault is an architectural boundary."""
    retired = []
    trap = None
    for entry in entries[:width]:
        if not entry["ready"]:
            break
        if entry["fault"]:
            trap = entry["id"]
            break
        if not entry["store_permit"]:
            # This field means 'required write-like commit permission' in the
            # scenario corpus; it is true for ordinary non-store operations.
            break
        retired.append(entry["id"])
    return retired, trap


class ProductG0Contract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))

    def test_status_and_qualified_boundaries(self):
        d = self.contract
        self.assertEqual(d["schema_version"], 1)
        self.assertEqual(d["status"], "proposed-g0-contract-not-rtl-implementation")
        self.assertEqual(d["baseline"]["main_sha"], MAIN_SHA)
        self.assertEqual(d["product"]["isa"], "RV64GC")
        self.assertEqual(d["product"]["abi"], "LP64D")
        self.assertEqual(d["baseline"]["product_target"], "not-implemented")
        self.assertEqual([p["id"] for p in d["profiles"]],
                         ["G1-minimal-ooo", "G2-width2", "G3-product-candidate"])
        self.assertTrue(all(not p["qualified"] for p in d["profiles"]))

    def test_every_mutable_state_has_one_owner(self):
        owners = self.contract["owners"]
        names = [o["id"] for o in owners]
        self.assertEqual(len(names), len(set(names)))
        self.assertGreaterEqual(len(names), 12)
        seen = {}
        for owner in owners:
            for key in owner["state"]:
                self.assertNotIn(key, seen, f"{key}: duplicate owners")
                seen[key] = owner["id"]
        for key in ("rob_lifetimes", "spec_int_rat", "physical_integer_values",
                    "store_age_queue", "architectural_fflags_fcsr",
                    "physical_transaction_table", "physical_memory_map"):
            self.assertIn(key, seen)

    def test_identities_are_distinct_and_owned(self):
        identities = self.contract["identities"]
        names = [i["id"] for i in identities]
        owners = {o["id"] for o in self.contract["owners"]}
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(set(names), {
            "RobToken", "ProducerTag", "ValueRef",
            "MemTxnId", "BranchEpoch", "FpEffect",
        })
        for item in identities:
            self.assertIn(item["owner"], owners)
            self.assertTrue(item["birth"] and item["end"] and item["validate"])
            self.assertNotIn(item["id"], item["not_equal_to"])
            self.assertTrue(set(item["not_equal_to"]).issubset(set(names)))

    def test_named_interfaces_link_existing_owners(self):
        interfaces = self.contract["required_interfaces"]
        names = [x["id"] for x in interfaces]
        self.assertEqual(len(names), len(set(names)))
        owners = {o["id"] for o in self.contract["owners"]}
        self.assertGreaterEqual(len(names), 8)
        for interface in interfaces:
            self.assertIn(interface["producer"], owners)
            self.assertIn(interface["consumer"], owners)
            self.assertNotEqual(interface["producer"], interface["consumer"])
            self.assertGreaterEqual(len(interface["fields"]), 3)
            self.assertTrue(interface["handshake"])
        for k in ("DispatchGroup", "RetireBundle", "Completion",
                  "MemoryRequest", "RecoveryCut", "ArchitectureTrace"):
            self.assertIn(k, names)

    def test_gates_and_explicit_unfrozen_decisions(self):
        d = self.contract
        ids = [item["id"] for item in d["invariants"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(len(ids), 12)
        self.assertEqual({x["id"] for x in d["open_decisions"]},
                         {"D01", "D02", "D03", "D04", "D05", "D06"})
        for x in d["open_decisions"]:
            self.assertTrue(x["must_close_before"].startswith("G"))
        self.assertTrue(any("dual retire" in x["rule"].lower()
                            for x in d["invariants"]))
        self.assertTrue(any("FP flags" in x["must"] or "FP flags" in x["when"]
                            for x in d["event_priority"]))

    def test_dispatch_scenarios_all_or_none(self):
        cases = self.contract["scenarios"]["dispatch"]
        self.assertGreaterEqual(len(cases), 5)
        for case in cases:
            with self.subTest(case=case["id"]):
                got = dispatch_accepted(case)
                self.assertEqual(got, case["expected"])
                self.assertIn(got, (0, case["requested"]))

    def test_retirement_scenarios_are_oldest_prefix_and_precise(self):
        cases = self.contract["scenarios"]["retire"]
        self.assertGreaterEqual(len(cases), 5)
        for case in cases:
            with self.subTest(case=case["id"]):
                got, trap = retire_prefix(case["entries"])
                self.assertEqual(got, case["expected_retired"])
                self.assertEqual(trap, case["expected_trap"])
                self.assertNotIn(trap, got)
                self.assertEqual(
                    got, [x["id"] for x in case["entries"][:len(got)]])
        self.assertIn("younger_fault", {c["id"] for c in cases})
        self.assertIn("head_fault", {c["id"] for c in cases})

    def test_existing_main_is_small_and_product_not_yet_implemented(self):
        core = SRC / "core" / "v2"
        text = (core / "TinyRobCommit.scala").read_text()
        self.assertIn("val Entries: Int = 4", text)
        self.assertIn("val GenerationBits: Int = 8", text)
        self.assertIn("val retire = Decoupled(new RobRetirement(xlen))", text)

        text = (core / "TinyPagedCore.scala").read_text()
        self.assertIn("new TinyLoadQueueMemoryBackend(", text)
        text = (core / "TinyDualReplaySafeLoadUnit.scala").read_text()
        self.assertIn("private val Slots = 2", text)
        text = (core / "TinyLoadQueueMemoryBackend.scala").read_text()
        self.assertIn("headParentMemory", text)
        self.assertIn("val loadUnit = Module(new TinyDualReplaySafeLoadUnit(", text)
        text = (core / "TinyDependency.scala").read_text()
        self.assertIn("private val rename = Mem(32,", text)
        text = (core / "TinyMemoryBackend.scala").read_text()
        self.assertIn("val completions = Module(new TinyCompletionArbiter(xlen, 3))", text)

        text = (SRC / "config" / "CoreConfig.scala").read_text()
        self.assertIn("Set('I', 'M', 'A', 'C')", text)
        self.assertIn("val hasC:", text)
        self.assertNotIn("val hasF:", text)
        text = (SRC / "memory" / "AetherMemLink.scala").read_text()
        self.assertIn("val txnId = UInt(txnIdBits.W)", text)
        self.assertIn("val attributes = new MemoryAttributes", text)

    def test_design_explains_implementation_limits(self):
        s = REVIEW.read_text(encoding="utf-8")
        for required in ("G0", "ROB4", "ROB64", "RAT", "PRF",
                         "LSQ", "LP64D", "same-cycle",
                         "not a production RTL qualification",
                         "unresolved"):
            self.assertIn(required, s)


if __name__ == "__main__":
    unittest.main()
