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

    def test_corrupted_non_object_manifest_is_a_miss_not_an_exception(self):
        cache.stamp(self.args)
        (self.rtl_dir / ".aethercore-rtl-qualified.json").write_text("[]\n")
        hit, reason, _ = cache.verify(self.args)
        self.assertFalse(hit)
        self.assertEqual(reason, "invalid-manifest")

    def test_identical_generated_sv_does_not_invalidate_compiled_model(self):
        cache.stamp(self.args)
        self.args.stage = "sim"
        cache.stamp(self.args)
        self.assertTrue(cache.verify(self.args)[0])
        # The parent build must qualify the newly generated RTL before
        # checking the model. Its byte-identical output needs no C++ rebuild.
        self.source.write_text("class Core { val unused = true }\n")
        self.args.stage = "rtl"
        cache.stamp(self.args)
        self.args.stage = "sim"
        self.assertTrue(cache.verify(self.args)[0])

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
        self.assertIn("stage=rtl result=MISS", workflow)
        self.assertIn("Save qualified Linux/OpenSBI baseline", workflow)
        self.assertIn("Save verified RV64 Linux GCC", workflow)
        self.assertIn("Save resolved Scala dependencies", workflow)
        producer = (ROOT / ".github/workflows/hosted-verilator-cache.yml").read_text()
        self.assertIn("queue: max", producer)
        self.assertIn("'.github/workflows/hosted-verilator-cache.yml'", workflow)

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
        rtl_base = build / "rtl"
        helper = integration / "tools/ci/l32_sim_artifact_cache.py"
        key_result = subprocess.run(
            [sys.executable, str(helper), "key", "--stage=rtl",
             f"--rtl-dir={rtl_base}", "--elaborate-main=mock.Elaborate"],
            check=True, cwd=integration, capture_output=True, text=True,
        )
        rtl = rtl_base / "by-source" / key_result.stdout.strip()
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

    def test_actual_make_miss_build_stamp_hit_and_regenerate_cleanly(self):
        integration = self.root / "fresh-repo"
        integration.mkdir()
        (integration / "tools/ci").mkdir(parents=True)
        shutil.copy2(ROOT / "Makefile.l32-linux-boot", integration / "Makefile.l32-linux-boot")
        shutil.copy2(SCRIPT, integration / "tools/ci/l32_sim_artifact_cache.py")
        scala = integration / "src/main/scala/Top.scala"
        scala.parent.mkdir(parents=True)
        scala.write_text("class Top {}\n")
        (integration / "sim").mkdir()
        (integration / "sim/opensbi_boot_main.cpp").write_text("// fake simulator\n")
        (integration / "sim/l32_opensbi_runtime.h").write_text("// header\n")
        (integration / "build.mill").write_text("// build\n")
        mill = integration / "mill"
        mill.write_text(
            "#!/bin/bash\n"
            "[[ ${FAIL_MILL:-0} == 0 ]] || exit 47\n"
            "[[ $1 == aethercore.runMain ]] || exit 48\n"
            "[[ $3 == --target-dir ]] || exit 49\n"
            "mkdir -p \"$4\"\n"
            "case $2 in *New*) name=New ;; *) name=Top ;; esac\n"
            "printf 'module %s; endmodule\\n' \"$name\" > \"$4/$name.sv\"\n"
        )
        mill.chmod(0o755)
        verilator = integration / "fake-verilator"
        verilator.write_text(
            "#!/bin/bash\n"
            "if [[ $1 == --version ]]; then echo 'Fake Verilator 5.024'; exit 0; fi\n"
            "[[ ${FAIL_VERILATOR:-0} == 0 ]] || exit 53\n"
            "obj=; top=; prev=\n"
            "for arg in \"$@\"; do\n"
            "  if [[ $prev == -Mdir ]]; then obj=$arg; fi\n"
            "  if [[ $prev == --top-module ]]; then top=$arg; fi\n"
            "  prev=$arg\n"
            "done\n"
            "[[ -n $obj && -n $top ]] || exit 54\n"
            "mkdir -p \"$obj\"\n"
            "printf '%s\\n' build >> verilator-invocations.log\n"
            "printf '#!/bin/bash\\n' > \"$obj/V$top\"\n"
            "printf 'echo Linux version 6.6.143\\n' >> \"$obj/V$top\"\n"
            "printf 'echo L32_RUNTIME_MILESTONE_PASS\\n' >> \"$obj/V$top\"\n"
            "printf 'echo runtime >> runtime-invocations.log\\n' >> \"$obj/V$top\"\n"
            "chmod +x \"$obj/V$top\"\n"
        )
        verilator.chmod(0o755)
        (integration / "firmware.bin").write_bytes(b"initial firmware")
        command = [
            "make", "-f", "Makefile.l32-linux-boot", "BUILD_DIR=build/probe",
            "TOP=Top", "ELABORATE_MAIN=mock.Elaborate",
            f"VERILATOR={verilator}", "FW_BIN=firmware.bin", "run-cached",
        ]

        def run(args=None, env=None):
            import os
            return subprocess.run(
                command if args is None else args, cwd=integration,
                capture_output=True, text=True, env=None if env is None else {**os.environ, **env},
            )

        cold = run()
        self.assertEqual(cold.returncode, 0, cold.stdout + cold.stderr)
        self.assertIn("AETHERCORE_SIM_CACHE_MISS stage=rtl", cold.stdout)
        self.assertIn("AETHERCORE_SIM_CACHE_MISS stage=sim", cold.stdout)
        self.assertIn("AETHERCORE_SIM_CACHE_STAMP stage=rtl", cold.stdout)
        self.assertIn("AETHERCORE_SIM_CACHE_STAMP stage=sim", cold.stdout)
        self.assertIn("stage=sim result=MISS", cold.stdout)
        self.assertEqual((integration / "verilator-invocations.log").read_text().count("build"), 1)

        warm = run()
        self.assertEqual(warm.returncode, 0, warm.stdout + warm.stderr)
        self.assertIn("AETHERCORE_SIM_CACHE_HIT stage=rtl", warm.stdout)
        self.assertIn("AETHERCORE_SIM_CACHE_HIT stage=sim", warm.stdout)
        self.assertIn("stage=sim result=HIT", warm.stdout)
        self.assertEqual((integration / "verilator-invocations.log").read_text().count("build"), 1)
        self.assertEqual((integration / "runtime-invocations.log").read_text().count("runtime"), 2)

        # An unqualified extra SV in the same source-keyed directory is
        # removed before regenerating; it cannot contaminate model inputs.
        current_dir = next((integration / "build/probe/rtl/by-source").iterdir())
        (current_dir / "Obsolete.sv").write_text("module Obsolete; endmodule\n")
        repaired = run()
        self.assertEqual(repaired.returncode, 0, repaired.stdout + repaired.stderr)
        self.assertIn("AETHERCORE_SIM_CACHE_MISS stage=rtl", repaired.stdout)
        self.assertIn("AETHERCORE_SIM_CACHE_HIT stage=sim", repaired.stdout)
        self.assertFalse((current_dir / "Obsolete.sv").exists())
        self.assertEqual((integration / "verilator-invocations.log").read_text().count("build"), 1)

        # A new source recipe elaborates in a new directory. If the generated
        # SV is identical, the previously compiled Verilator model is reusable.
        scala.write_text("class Top { val unrelated = 1 }\n")
        refreshed = run()
        self.assertEqual(refreshed.returncode, 0, refreshed.stdout + refreshed.stderr)
        self.assertIn("AETHERCORE_SIM_CACHE_MISS stage=rtl", refreshed.stdout)
        self.assertIn("AETHERCORE_SIM_CACHE_HIT stage=sim", refreshed.stdout)
        self.assertEqual((integration / "verilator-invocations.log").read_text().count("build"), 1)

        # A changed elaborator now emits a different module/file. Never feed
        # the old Top.sv to Verilator in the same RTL directory.
        new_command = [x.replace("mock.Elaborate", "mock.ElaborateNew") for x in command]
        new_run = run(new_command)
        self.assertEqual(new_run.returncode, 0, new_run.stdout + new_run.stderr)
        self.assertIn("AETHERCORE_SIM_CACHE_MISS stage=sim", new_run.stdout)
        self.assertEqual((integration / "verilator-invocations.log").read_text().count("build"), 2)
        rtl_root = integration / "build/probe/rtl/by-source"
        new_dirs = [p for p in rtl_root.iterdir() if (p / "New.sv").exists()]
        self.assertEqual(len(new_dirs), 1)
        self.assertEqual([p.name for p in new_dirs[0].glob("*.sv")], ["New.sv"])
        self.assertEqual((integration / "runtime-invocations.log").read_text().count("runtime"), 5)

        # A failed rebuild cannot publish a stale or misleading "this run"
        # qualification, even if an earlier invocation in this build tree
        # successfully compiled and passed Linux.
        runner = integration / "sim/opensbi_boot_main.cpp"
        runner.write_text("// changed simulator, requires recompilation\n")
        failed = run(env={"FAIL_VERILATOR": "1"})
        self.assertNotEqual(failed.returncode, 0, failed.stdout)
        self.assertFalse((integration / "build/probe/.sim-build-qualified-this-run").exists())
        self.assertFalse((integration / "build/probe/obj/.aethercore-sim-qualified.json").exists())
        recovered = run()
        self.assertEqual(recovered.returncode, 0, recovered.stdout + recovered.stderr)
        self.assertIn("AETHERCORE_SIM_CACHE_MISS stage=sim", recovered.stdout)
        self.assertEqual((integration / "verilator-invocations.log").read_text().count("build"), 3)
        self.assertEqual((integration / "runtime-invocations.log").read_text().count("runtime"), 6)
        stage_log = (integration / "build/probe/logs/cache-stages.log").read_text()
        self.assertIn("stage=sim result=MISS elapsed-seconds=", stage_log)
        self.assertIn("stage=sim result=HIT elapsed-seconds=", stage_log)
        self.assertIn("stage=linux-runtime exit=0 elapsed-seconds=", stage_log)

    def test_actual_make_failed_elaboration_never_stamps_qualified_rtl(self):
        # A failed build must not be disguised as success by a trailing echo.
        integration = self.root / "failed-repo"
        integration.mkdir()
        (integration / "tools/ci").mkdir(parents=True)
        shutil.copy2(ROOT / "Makefile.l32-linux-boot", integration / "Makefile.l32-linux-boot")
        shutil.copy2(SCRIPT, integration / "tools/ci/l32_sim_artifact_cache.py")
        (integration / "src/main/scala").mkdir(parents=True)
        (integration / "src/main/scala/Top.scala").write_text("class Top {}\n")
        (integration / "build.mill").write_text("// fake\n")
        mill = integration / "mill"
        mill.write_text("#!/bin/sh\nexit 51\n")
        mill.chmod(0o755)
        outcome = subprocess.run(
            ["make", "-f", "Makefile.l32-linux-boot", "BUILD_DIR=build/probe", "rtl-cached"],
            cwd=integration, capture_output=True, text=True,
        )
        self.assertNotEqual(outcome.returncode, 0, outcome.stdout)
        self.assertFalse(list((integration / "build/probe/rtl").rglob(".aethercore-rtl-qualified.json")))


if __name__ == "__main__":
    unittest.main()
