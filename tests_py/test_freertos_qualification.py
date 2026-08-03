import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "software" / "freertos" / "FreeRTOS-Kernel.lock"
FETCH = ROOT / "tools" / "fetch_freertos_kernel.sh"
AUDIT = ROOT / "tools" / "audit_freertos_riscv_port.py"
CSR_FILE = ROOT / "src" / "main" / "scala" / "aethercore" / "core" / "MachineCsrFile.scala"
CHIP_EXTENSION = (
    Path("portable")
    / "GCC"
    / "RISC-V"
    / "chip_specific_extensions"
    / "RISCV_MTIME_CLINT_no_extensions"
    / "freertos_risc_v_chip_specific_extensions.h"
)


class FreeRtosQualificationTest(unittest.TestCase):
    def run_command(self, *arguments: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(argument) for argument in arguments],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def lock_values(self) -> dict[str, str]:
        return dict(
            line.split("=", 1)
            for line in LOCK.read_text(encoding="utf-8").splitlines()
            if line and not line.startswith("#")
        )

    def make_fixture(self, root: Path, include_mhartid: bool = True) -> None:
        port = root / "portable" / "GCC" / "RISC-V"
        port.mkdir(parents=True)
        hart_fragment = "csrr %0, mhartid\n" if include_mhartid else ""
        (port / "port.c").write_text(
            "\n".join(
                (
                    "configMTIME_BASE_ADDRESS",
                    "configMTIMECMP_BASE_ADDRESS",
                    hart_fragment,
                    "csrs mie, %0",
                    "vPortSetupTimerInterrupt",
                    "xPortStartScheduler",
                    "xPortStartFirstTask",
                )
            ),
            encoding="utf-8",
        )
        (port / "portASM.S").write_text(
            "\n".join(
                (
                    "portUPDATE_MTIMER_COMPARE_REGISTER",
                    "xPortStartFirstTask",
                    "freertos_risc_v_trap_handler",
                    "freertos_risc_v_exception_handler",
                    "freertos_risc_v_interrupt_handler",
                    "freertos_risc_v_mtimer_interrupt_handler",
                    "mstatus",
                    "mepc",
                    "mcause",
                    "mret",
                )
            ),
            encoding="utf-8",
        )
        (port / "portmacro.h").write_text(
            "\n".join(
                (
                    "portYIELD()",
                    '"ecall"',
                    '"csrc mstatus, 8"',
                    '"csrs mstatus, 8"',
                    "configMTIME_BASE_ADDRESS",
                    "configMTIMECMP_BASE_ADDRESS",
                    "__builtin_clz",
                )
            ),
            encoding="utf-8",
        )
        extension = root / CHIP_EXTENSION
        extension.parent.mkdir(parents=True)
        extension.write_text(
            "\n".join(
                (
                    "portasmHAS_SIFIVE_CLINT",
                    "portasmHAS_MTIME",
                    "portasmADDITIONAL_CONTEXT_SIZE",
                    "portasmSAVE_ADDITIONAL_REGISTERS",
                    "portasmRESTORE_ADDITIONAL_REGISTERS",
                )
            ),
            encoding="utf-8",
        )

    def test_lock_pins_exact_v1130_sources_and_platform_addresses(self) -> None:
        values = self.lock_values()
        self.assertEqual(values["release"], "V11.3.0")
        self.assertEqual(
            values["revision"],
            "9b777ae5c5b8e9e456065a00294d1e5f5f9facf5",
        )
        self.assertEqual(len(values["revision"]), 40)
        self.assertEqual(values["initial_profile"], "rv32im_zicsr_m")
        self.assertEqual(values["initial_harts"], "1")
        self.assertEqual(values["expected_mhartid"], "0")
        self.assertEqual(values["mtime_address"], "0x0200bff8")
        self.assertEqual(values["mtimecmp_address"], "0x02004000")
        self.assertEqual(
            values["chip_extension_directory"],
            CHIP_EXTENSION.parent.as_posix(),
        )
        for key in (
            "port_c_blob_sha",
            "port_asm_blob_sha",
            "portmacro_blob_sha",
            "chip_extension_blob_sha",
        ):
            self.assertEqual(len(values[key]), 40)
            int(values[key], 16)

    def test_fetch_script_is_bounded_exact_and_cache_validated(self) -> None:
        syntax = self.run_command("bash", "-n", FETCH)
        self.assertEqual(syntax.returncode, 0, syntax.stderr)
        text = FETCH.read_text(encoding="utf-8")
        self.assertIn("for attempt in 1 2 3 4 5", text)
        self.assertIn('timeout "$FETCH_TIMEOUT"', text)
        self.assertIn("http.version=HTTP/1.1", text)
        self.assertIn("git -C \"$tree\" hash-object portable/GCC/RISC-V/port.c", text)
        self.assertIn('git -C "$tree" hash-object "$CHIP_EXTENSION_PATH"', text)
        self.assertIn("status --porcelain --untracked-files=all", text)
        self.assertIn("checkout --quiet --detach", text)

    def test_offline_port_contract_records_the_initial_architecture_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "FreeRTOS-Kernel"
            output = Path(temporary) / "contract.json"
            self.make_fixture(source)
            result = self.run_command(sys.executable, AUDIT, source, output)
            self.assertEqual(result.returncode, 0, result.stderr)
            contract = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(contract["status"], "PASS")
            self.assertEqual(contract["upstream"]["release"], "V11.3.0")
            self.assertEqual(contract["initial_target"]["profile"], "rv32im_zicsr_m")
            self.assertEqual(contract["initial_target"]["mhartid"], 0)
            self.assertEqual(contract["initial_target"]["additional_context_registers"], 0)
            self.assertIn("mhartid", contract["required_architecture"]["csrs"])
            self.assertIn("chip_extension.h", contract["files"])
            self.assertIn(
                "startup and linker script",
                contract["aethercore_boundary"]["platform_glue_pending"],
            )
            self.assertIn("A", contract["aethercore_boundary"]["not_required_for_initial_gate"])

    def test_port_contract_rejects_a_source_that_drops_mhartid(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "FreeRTOS-Kernel"
            output = Path(temporary) / "contract.json"
            self.make_fixture(source, include_mhartid=False)
            result = self.run_command(sys.executable, AUDIT, source, output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("mhartid", result.stderr)
            self.assertFalse(output.exists())

    def test_cpu_source_exposes_only_the_single_hart_read_contract(self) -> None:
        text = CSR_FILE.read_text(encoding="utf-8")
        self.assertIn("val Mhartid: Int = 0xf14", text)
        self.assertIn("is(MachineCsrAddress.Mhartid.U)", text)
        self.assertIn("io.readData := 0.U", text)
        self.assertNotIn("is(MachineCsrAddress.Mhartid.U) { mhartid :=", text)


if __name__ == "__main__":
    unittest.main()
