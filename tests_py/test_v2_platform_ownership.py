import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE_V2 = ROOT / "src/main/scala/aethercore/core/v2"
AETHER_MEM = ROOT / "src/main/scala/aethercore/memory/AetherMemLink.scala"
PAGED = CORE_V2 / "TinyPagedCore.scala"
OPEN_SBI_PLATFORM = ROOT / "src/main/scala/aethercore/sim/AetherCoreV2OpenSbiRV64SimTop.scala"


class V2PlatformOwnershipSourceContract(unittest.TestCase):
    def test_aethermem_is_semantic_physical_memory_not_external_bus_protocol(self):
        source = AETHER_MEM.read_text(encoding="utf-8")

        self.assertIn("Core-internal physical-memory operation", source)
        self.assertIn("Bus protocols such as AXI remain outside this contract", source)
        self.assertIn("val txnId = UInt(txnIdBits.W)", source)
        self.assertIn("val op = AetherMemOp()", source)
        self.assertIn("val paddr = UInt(paddrBits.W)", source)
        self.assertIn("val attributes = new MemoryAttributes", source)
        self.assertIn("class AetherMemResponse", source)

        # Do not let the semantic core-memory contract become an AXI/TileLink
        # channel bundle. Mentioning those protocols in architecture comments is
        # fine; declaring protocol channel ownership here is not.
        self.assertNotIn("class Axi", source)
        self.assertNotIn("class AXI", source)
        self.assertNotIn("class TileLink", source)
        self.assertNotIn("val aw =", source)
        self.assertNotIn("val ar =", source)

    def test_pma_attributes_remain_explicit_at_the_core_memory_seam(self):
        source = AETHER_MEM.read_text(encoding="utf-8")
        for field in (
            "cacheable",
            "idempotent",
            "sideEffecting",
            "ordered",
            "executable",
            "supportsAtomic",
            "supportsPartial",
        ):
            self.assertIn(f"val {field} = Bool()", source)

    def test_paged_core_exposes_generic_pma_and_aethermem_seams(self):
        source = PAGED.read_text(encoding="utf-8")

        self.assertIn("val resolvedPhysicalValid = Output(Bool())", source)
        self.assertIn("val resolvedPhysicalAddress = Output(UInt(PhysicalBits.W))", source)
        self.assertIn("val resolvedAttributes = Input(new MemoryAttributes)", source)
        self.assertIn("val memoryRequest = Decoupled(new AetherMemRequest", source)
        self.assertIn("val memoryResponse = Flipped(Decoupled(new AetherMemResponse", source)
        self.assertIn("backend.io.resolvedAttributes := io.resolvedAttributes", source)

        # CPU composition may consume construction geometry/reset state, but it
        # must not know board-device decode addresses.
        for forbidden in (
            "config.platform.uartAddress",
            "config.platform.exitAddress",
            "config.platform.mtimeAddress",
            "config.platform.mtimecmpAddress",
        ):
            self.assertNotIn(forbidden, source)

    def test_no_v2_cpu_owner_contains_qualified_board_device_map(self):
        sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(CORE_V2.glob("*.scala"))
        )

        for forbidden in (
            "uartAddress",
            "exitAddress",
            "mtimeAddress",
            "mtimecmpAddress",
            "10000000",  # qualified UART base
            "0c000000",  # qualified PLIC base
            "0200bff8",  # qualified mtime address
            "02004000",  # qualified mtimecmp address
        ):
            self.assertNotIn(forbidden, sources)

    def test_opensbi_shell_owns_pma_mmio_and_interrupt_topology(self):
        source = OPEN_SBI_PLATFORM.read_text(encoding="utf-8")

        self.assertIn("private val ramBase =", source)
        self.assertIn("private val plicBase =", source)
        self.assertIn("val resolvedRam = resolvedAddress >= ramBase.U", source)
        self.assertIn("core.io.resolvedAttributes.cacheable := resolvedRam", source)
        self.assertIn("core.io.resolvedAttributes.sideEffecting := !resolvedRam", source)
        self.assertIn("core.io.resolvedAttributes.supportsAtomic := resolvedRam", source)

        self.assertIn("val pendingUart =", source)
        self.assertIn("val pendingExit =", source)
        self.assertIn("val pendingTimer =", source)
        self.assertIn("val pendingPlic =", source)
        self.assertIn("val pendingMmio = pendingUart || pendingExit || pendingTimer || pendingPlic", source)

        self.assertIn("private val supervisorUartSourceId = 10", source)
        self.assertIn("val supervisorPlic = Module(new MachinePlicMmio", source)
        self.assertIn("core.io.supervisorExternalInterrupt.get := supervisorPlic.io.interrupt", source)
        self.assertIn("assert(!(pendingMmio && pendingAtomic)", source)


if __name__ == "__main__":
    unittest.main()
