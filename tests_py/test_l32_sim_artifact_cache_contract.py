import argparse
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/l32_sim_artifact_cache.py"
SPEC = importlib.util.spec_from_file_location("l32_sim_artifact_cache", SCRIPT)
cache = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(cache)


class L32SimArtifactCacheContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "src/main/scala/aethercore/Core.scala"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("class Core {}\n")
        (self.root / "build.mill").write_text("// build\n")
        (self.root / "mill").write_text("#!/bin/sh\n")
        self.runner = self.root / "sim/opensbi_boot_main.cpp"
        self.runner.parent.mkdir()
        self.runner.write_text("// host runner\n")
        (self.root / "sim/l32_opensbi_runtime.h").write_text("// memory\n")
        (self.root / "Makefile.l32-linux-boot").write_text("# build recipe\n")
        helper = self.root / "tools/ci/l32_sim_artifact_cache.py"
        helper.parent.mkdir(parents=True)
        helper.write_text("# cached build recipe implementation\n")
        self.rtl_dir = self.root / "build/rtl"
        self.rtl_dir.mkdir(parents=True)
        self.sv = self.rtl_dir / "Core.sv"
        self.sv.write_text("module Core; endmodule\n")
        self.obj_dir = self.root / "build/obj"
        self.obj_dir.mkdir()
        self.binary = self.obj_dir / "VCore"
        self.binary.write_bytes(b"mock compiled simulator")
        self.binary.chmod(0o755)
        self.args = argparse.Namespace(
            stage="rtl", rtl_dir=str(self.rtl_dir), obj_dir=str(self.obj_dir),
            top="Core", elaborate_main="aethercore.Elaborate", verilator="verilator",
            verilator_model_opt="-O3", verilator_threads="1",
            verilator_make_opt='-MAKEFLAGS "OPT_FAST=-O3"',
            sim_cxxflags="-O3 -march=native", cxx="g++",
            runner="sim/opensbi_boot_main.cpp",
        )
        self.original_root = cache.ROOT
        cache.ROOT = self.root
        self.addCleanup(setattr, cache, "ROOT", self.original_root)
        tool_patch = patch.object(cache, "tool_identity", return_value={"version": "pinned"})
        cpu_patch = patch.object(cache, "cpu_identity", return_value="host-cpu-A")
        tool_patch.start()
        cpu_patch.start()
        self.addCleanup(tool_patch.stop)
        self.addCleanup(cpu_patch.stop)

    def test_rtl_hit_requires_source_recipe_and_generated_sv_sha(self):
        self.assertFalse(cache.verify(self.args)[0])
        cache.stamp(self.args)
        self.assertTrue(cache.verify(self.args)[0])
        self.sv.write_text("module Core; wire changed; endmodule\n")
        self.assertFalse(cache.verify(self.args)[0])

    def test_local_dirty_or_untracked_chisel_source_invalidates_cache(self):
        cache.stamp(self.args)
        self.source.write_text("class Core { val newFeature = true }\n")
        self.assertFalse(cache.verify(self.args)[0])
        self.source.write_text("class Core {}\n")
        self.assertTrue(cache.verify(self.args)[0])
        new_file = self.source.parent / "Added.scala"
        new_file.write_text("class Added {}\n")
        self.assertFalse(cache.verify(self.args)[0])

    def test_model_requires_qualified_rtl_then_binary_and_recipe_sha(self):
        self.args.stage = "sim"
        with self.assertRaisesRegex(ValueError, "unqualified RTL"):
            cache.stamp(self.args)
        self.args.stage = "rtl"
        cache.stamp(self.args)
        self.args.stage = "sim"
        cache.stamp(self.args)
        self.assertTrue(cache.verify(self.args)[0])
        self.binary.write_bytes(b"corrupt executable")
        self.assertFalse(cache.verify(self.args)[0])

    def test_model_changes_invalidate_without_rebuilding_unchanged_rtl(self):
        cache.stamp(self.args)
        self.args.stage = "sim"
        cache.stamp(self.args)
        self.args.sim_cxxflags = "-O2 -march=native"
        self.assertFalse(cache.verify(self.args)[0])
        self.args.sim_cxxflags = "-O3 -march=native"
        self.runner.write_text("// new host memory behavior\n")
        self.assertFalse(cache.verify(self.args)[0])
        self.args.stage = "rtl"
        self.assertTrue(cache.verify(self.args)[0])

    def test_runtime_firmware_and_milestone_do_not_invalidate_model(self):
        cache.stamp(self.args)
        self.args.stage = "sim"
        cache.stamp(self.args)
        # Neither runtime image contents nor runtime pass/fail logs are part
        # of the compiled model identity. The caller still reruns Linux.
        payload = self.root / "firmware.bin"
        payload.write_bytes(b"old Linux")
        self.assertTrue(cache.verify(self.args)[0])
        payload.write_bytes(b"new Linux")
        self.assertTrue(cache.verify(self.args)[0])

    def test_model_fences_different_cpu_when_native_optimization_is_enabled(self):
        cache.stamp(self.args)
        self.args.stage = "sim"
        cache.stamp(self.args)
        with patch.object(cache, "cpu_identity", return_value="host-cpu-B"):
            self.assertFalse(cache.verify(self.args)[0])

    def test_boot_and_forkserver_runners_are_separately_qualified(self):
        fork_runner = self.root / "sim/opensbi_forkserver_main.cpp"
        fork_runner.write_text("// forkserver\n")
        cache.stamp(self.args)
        self.args.stage = "sim"
        cache.stamp(self.args)
        self.assertTrue(cache.verify(self.args)[0])
        self.args.runner = "sim/opensbi_forkserver_main.cpp"
        self.assertFalse(cache.verify(self.args)[0])

    def test_legacy_rv64_shim_header_change_invalidates_model(self):
        shim = self.root / "sim/rv64_opensbi_shim/VAetherCoreOpenSbiSimTop.h"
        shim.parent.mkdir(parents=True)
        shim.write_text("// legacy shim v1\n")
        cache.stamp(self.args)
        self.args.stage = "sim"
        cache.stamp(self.args)
        self.assertTrue(cache.verify(self.args)[0])
        shim.write_text("// legacy shim v2\n")
        self.assertFalse(cache.verify(self.args)[0])

    def test_makefile_exposes_checked_cached_run_and_workflow_uses_it(self):
        makefile = (ROOT / "Makefile.l32-linux-boot").read_text()
        workflow = (ROOT / ".github/workflows/v2-soc-virtual-fpga-linux-pid1.yml").read_text()
        self.assertIn("run-cached: sim-cached", makefile)
        self.assertIn("sim-cached: rtl-cached", makefile)
        self.assertIn("check --stage=sim", makefile)
        self.assertIn("stamp --stage=sim", makefile)
        self.assertIn("run-cached", workflow)
        self.assertIn("actions/cache/restore@v4", workflow)
        self.assertIn("actions/cache/save@v4", workflow)

    def test_actual_make_cached_run_reuses_model_but_reruns_firmware(self):
        # This tiny private repo has a fake Verilator and a fake simulator.
        # A cache miss would call its failing Mill stub; neither real Mill nor
        # an RTL/Linux toolchain is needed for the integration test.
        integration = self.root / "mini-repo"
        integration.mkdir()
        (integration / "tools/ci").mkdir(parents=True)
        shutil.copy2(ROOT / "Makefile.l32-linux-boot", integration / "Makefile.l32-linux-boot")
        shutil.copy2(SCRIPT, integration / "tools/ci/l32_sim_artifact_cache.py")
        (integration / "src/main/scala").mkdir(parents=True)
        (integration / "src/main/scala/Top.scala").write_text("class Top {}\n")
        (integration / "sim").mkdir()
        (integration / "sim/opensbi_boot_main.cpp").write_text("// mock\n")
        (integration / "sim/l32_opensbi_runtime.h").write_text("// mock\n")
        (integration / "build.mill").write_text("// mock\n")
        mill = integration / "mill"
        mill.write_text("#!/bin/sh\necho MILL_SHOULD_NOT_RUN >&2\nexit 42\n")
        mill.chmod(0o755)
        verilator = integration / "fake-verilator"
        verilator.write_text("#!/bin/sh\n[ \"$1\" = --version ] && { echo 'Fake Verilator'; exit 0; }\nexit 43\n")
        verilator.chmod(0o755)
        build = integration / "build/probe"
        rtl = build / "rtl"
        rtl.mkdir(parents=True)
        (rtl / "Top.sv").write_text("module Top; endmodule\n")
        obj = build / "obj"
        obj.mkdir()
        exe = obj / "VTop"
        exe.write_text("#!/bin/sh\necho 'OpenSBI v1.6'\necho 'Linux version 6.6.143'\necho 'L32_RUNTIME_MILESTONE_PASS'\n")
        exe.chmod(0o755)
        firmware = integration / "firmware.bin"
        firmware.write_bytes(b"first image")
        cache_args = [
            f"--rtl-dir={rtl}", f"--obj-dir={obj}", "--top=Top",
            "--elaborate-main=mock.Elaborate", f"--verilator={verilator}",
            "--verilator-model-opt=-O3", "--verilator-threads=1",
            '--verilator-make-opt=-MAKEFLAGS "OPT_FAST=-O3 OPT_GLOBAL=-O3"',
            "--sim-cxxflags=-std=c++20 -O3 -march=native", "--cxx=g++",
        ]
        helper = integration / "tools/ci/l32_sim_artifact_cache.py"
        for stage in ("rtl", "sim"):
            subprocess.run(
                [sys.executable, str(helper), "stamp", f"--stage={stage}", *cache_args],
                check=True, cwd=integration, capture_output=True, text=True,
            )
        make = [
            "make", "-f", "Makefile.l32-linux-boot", "BUILD_DIR=build/probe",
            "TOP=Top", "ELABORATE_MAIN=mock.Elaborate",
            f"VERILATOR={verilator}", "SIM_CXXFLAGS=-std=c++20 -O3 -march=native",
            "FW_BIN=firmware.bin", "run-cached",
        ]
        first = subprocess.run(make, cwd=integration, capture_output=True, text=True)
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        self.assertIn("AETHERCORE_SIM_CACHE_HIT stage=rtl", first.stdout)
        self.assertIn("AETHERCORE_SIM_CACHE_HIT stage=sim", first.stdout)
        self.assertIn("L32_RUNTIME_MILESTONE_PASS", first.stdout)
        firmware.write_bytes(b"second image, same compiled model")
        second = subprocess.run(make, cwd=integration, capture_output=True, text=True)
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertIn("AETHERCORE_SIM_CACHE_HIT stage=sim", second.stdout)
        self.assertIn("L32_RUNTIME_MILESTONE_PASS", second.stdout)


if __name__ == "__main__":
    unittest.main()
