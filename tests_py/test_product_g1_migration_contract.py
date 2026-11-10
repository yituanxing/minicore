"""G1 migration decisions are file-grounded, bounded and fail closed."""
import json
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
G0 = ROOT / "docs/architecture/rv64gc_g0_contract.json"
MAP = ROOT / "docs/architecture/rv64gc_g1_migration_map.json"
DESIGN = ROOT / "docs/architecture/RV64GC_G1_MIGRATION_DECISION.md"


class ProductG1MigrationContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.g0 = json.loads(G0.read_text(encoding="utf-8"))
        cls.plan = json.loads(MAP.read_text(encoding="utf-8"))

    def test_decisions_are_selected_not_falsely_frozen(self):
        decisions = {d["id"]: d for d in self.g0["g1_selected_decisions"]}
        self.assertEqual(set(decisions), {"D02", "D06"})
        for name in decisions:
            self.assertEqual(decisions[name]["status"],
                             "selected-for-G1-review-not-RTL-frozen")
        pending = {d["id"] for d in self.g0["open_decisions"]}
        self.assertEqual(pending, {"D01", "D02", "D03", "D04", "D05", "D06"})
        self.assertEqual(self.g0["baseline"]["product_target"], "not-implemented")

    def test_prf_capacity_and_rename_ownership(self):
        for profile in self.g0["profiles"]:
            with self.subTest(profile=profile["id"]):
                self.assertGreaterEqual(profile["physical_int"], 32 + profile["rob"])
        self.assertEqual(
            next(p for p in self.g0["profiles"]
                 if p["id"] == "G3-product-candidate")["physical_int"], 96)
        d = self.g0["g1_selected_decisions"][0]
        self.assertEqual(d["id"], "D02")
        self.assertEqual(d["initial"]["integer_prf"], 48)
        self.assertEqual(d["initial"]["rob_entries"], 8)
        self.assertEqual(d["initial"]["prf_read_ports"], 2)
        self.assertEqual(d["initial"]["prf_write_ports"], 1)
        for key in ("old mapping", "reverse", "squash", "younger"):
            self.assertIn(key, d["recovery"].lower())
        self.assertIn("committed RAT", d["commit"])

    def test_file_classification_is_real_and_exhaustive(self):
        plan = self.plan
        self.assertEqual(plan["schema_version"], 1)
        self.assertEqual(plan["status"], "selected-G1-migration-plan-not-implemented")
        self.assertEqual(plan["product_namespace"],
                         "src/main/scala/aethercore/core/product/")
        paths = [x["source"] for x in plan["modules"]]
        self.assertEqual(len(paths), len(set(paths)))
        self.assertGreaterEqual(len(paths), 16)
        self.assertTrue(all((ROOT / x).is_file() for x in paths),
                        "migration plan refers to a nonexistent source file")
        allowed = {"extract-once", "reuse-boundary", "replace", "retain-reference"}
        self.assertEqual({x["mode"] for x in plan["modules"]}, allowed)
        self.assertEqual(
            [x["id"] for x in plan["batches"]], ["S", "B", "I"])
        self.assertTrue(all(x["batch"] in {"S","B","I"}
                            for x in plan["modules"]))

    def test_no_legacy_backend_becomes_product_state_owner(self):
        plan = self.plan
        sources = {x["source"]: x for x in plan["modules"]}
        for name in ("TinyRobCommit.scala", "TinyDependency.scala",
                     "TinyPhysicalIssue.scala", "TinyLoadQueueMemoryBackend.scala"):
            source = ("src/main/scala/aethercore/core/v2/" + name)
            self.assertEqual(sources[source]["mode"], "replace")
            self.assertEqual(sources[source]["batch"], "B")
        product = ROOT / plan["product_namespace"]
        if product.exists():
            forbidden = ("new TinyRob(", "new TinyDependencyBackend(",
                         "new TinyMemoryBackend(",
                         "new TinyLoadQueueMemoryBackend(")
            for path in product.rglob("*.scala"):
                content = path.read_text(encoding="utf-8")
                for token in forbidden:
                    self.assertNotIn(token, content, str(path))

    def test_legacy_v0_soc_is_preserved(self):
        modules = self.plan["modules"]
        legacy = [m for m in modules if m["mode"] == "retain-reference"]
        self.assertEqual(len(legacy), 2)
        self.assertTrue(all(m["batch"] == "I" for m in legacy))
        self.assertTrue(any(m["source"].endswith("AetherCoreV2Complex.scala")
                            for m in legacy))
        self.assertTrue(any(m["source"].endswith("AetherCoreV2FpgaSoC.scala")
                            for m in legacy))

    def test_bounded_batches_and_real_qualification_gates(self):
        batch = {x["id"]:x for x in self.plan["batches"]}
        self.assertIn("semantic", batch["S"]["deliver"].lower())
        self.assertIn("rename", batch["B"]["deliver"].lower())
        self.assertIn("opt-in", batch["I"]["deliver"].lower())
        self.assertTrue(any("Spike" in g for g in batch["B"]["gates"]))
        self.assertTrue(any("v2" in g for g in batch["S"]["gates"]))
        self.assertIn("not-implemented", self.plan["status"])
        self.assertTrue(any("Fmax" in x for x in self.plan["forbidden"]))

    def test_decision_document_explains_actual_modification_and_exit(self):
        doc = DESIGN.read_text(encoding="utf-8")
        for keyword in ("D02", "D06", "G1", "G2", "96",
                        "ROB4", "ROB64", "undo", "FreeList", "RAT",
                        "ProductCore", "TinySemanticDecode",
                        "AetherMem", "Fast Gate", "exit criteria",
                        "do not modify v0"):
            self.assertIn(keyword, doc)


if __name__ == "__main__":
    unittest.main()
