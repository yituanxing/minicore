import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE_V2 = ROOT / "src/main/scala/aethercore/core/v2"
AETHER_MEM = ROOT / "src/main/scala/aethercore/memory/AetherMemLink.scala"
PAGED = CORE_V2 / "TinyPagedCore.scala"
SOC_PLATFORM = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2LinuxSoC.scala"
SOC_FABRIC = ROOT / "src/main/scala/aethercore/soc/AetherSoCPlatformFabric.scala"
SOC_ADDRESS_MAP = ROOT / "src/main/scala/aethercore/soc/AetherSoCAddressMap.scala"
CPU_COMPLEX = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2Complex.scala"
UART_PERIPHERAL = ROOT / "src/main/scala/aethercore/soc/peripheral/AetherUart16550.scala"
MTIMER_PERIPHERAL = ROOT / "src/main/scala/aethercore/soc/peripheral/AetherAclintMtimer.scala"
PLIC_PERIPHERAL = ROOT / "src/main/scala/aethercore/soc/peripheral/AetherPlic.scala"
SIM_WRAPPER = ROOT / "src/main/scala/aethercore/sim/AetherCoreV2OpenSbiRV64SimTop.scala"


class V2PlatformOwnershipSourceContract(unittest.TestCase):
    def test_aethermem_is_semantic_physical_memory_not_external_bus_protocol(self):
        source = AETHER_MEM.read_text(encoding="utf-8")

        self.assertIn("Core-internal physical-memory operation", source)
        self.assertIn("Bus protocols such as AXI remain", source)
        self.assertIn("outside this contract", source)
        self.assertIn("val txnId = UInt(txnIdBits.W)", source)
        self.assertIn("val op = AetherMemOp()", source)
        self.assertIn("val paddr = UInt(addrBits.W)", source)
        self.assertIn("val attributes = new MemoryAttributes", source)
        self.assertIn("class AetherMemResponse", source)

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
            "10000000",
            "0c000000",
            "0200bff8",
            "02004000",
        ):
            self.assertNotIn(forbidden, sources)

    def test_cpu_complex_owns_private_cache_not_soc_devices(self):
        source = CPU_COMPLEX.read_text(encoding="utf-8")

        self.assertIn("class AetherCoreV2Complex(", source)
        self.assertIn("val dcache = Module(new AetherDirectMappedReadCache(", source)
        self.assertIn("dcache.io.upstreamRequest <> core.io.memoryRequest", source)
        self.assertIn("val supervisorExternalInterrupt = Input(Bool())", source)
        self.assertIn("val resolvedAttributes = Input(new MemoryAttributes)", source)

        for forbidden in (
            "config.platform.uartAddress",
            "config.platform.exitAddress",
            "config.platform.mtimeAddress",
            "config.platform.mtimecmpAddress",
            "MachinePlicMmio",
            "private val ramBase =",
            "private val plicBase =",
            "AetherMemToAxi4Bridge",
        ):
            self.assertNotIn(forbidden, source)

    def test_uart_register_state_is_peripheral_owned(self):
        peripheral = UART_PERIPHERAL.read_text(encoding="utf-8")
        fabric = SOC_FABRIC.read_text(encoding="utf-8")
        platform = SOC_PLATFORM.read_text(encoding="utf-8")

        self.assertIn("class AetherUart16550(", peripheral)
        self.assertIn("private val lcr = RegInit", peripheral)
        self.assertIn("private val ier = RegInit", peripheral)
        self.assertIn("new Queue(UInt(8.W), rxDepth)", peripheral)
        self.assertIn("io.txValid :=", peripheral)
        self.assertIn("io.interrupt := combinedInterrupt", peripheral)

        self.assertIn("private val uart = Module(new AetherUart16550(", fabric)
        self.assertIn("uart.io.request := pendingUart", fabric)
        self.assertIn("uartComplete := responseFire", fabric)
        self.assertNotIn("Module(new AetherUart16550(", platform)
        for forbidden in (
            "val uartLcr = RegInit",
            "val uartIer = RegInit",
            "val uartDll = RegInit",
            "val uartRx = Module(new Queue",
        ):
            self.assertNotIn(forbidden, platform)

    def test_mtimer_state_is_peripheral_owned(self):
        peripheral = MTIMER_PERIPHERAL.read_text(encoding="utf-8")
        fabric = SOC_FABRIC.read_text(encoding="utf-8")
        platform = SOC_PLATFORM.read_text(encoding="utf-8")

        self.assertIn("class AetherAclintMtimer(", peripheral)
        self.assertIn("private val mtime = RegInit", peripheral)
        self.assertIn("private val mtimecmp = RegInit", peripheral)
        self.assertIn("io.interrupt := mtime >= mtimecmp", peripheral)
        self.assertIn("private val terminalFire = io.request && io.complete", peripheral)

        self.assertIn("private val timer = Module(new AetherAclintMtimer(", fabric)
        self.assertIn("timer.io.request := pendingTimer", fabric)
        self.assertIn("timerComplete := responseFire", fabric)
        self.assertIn("io.time := timer.io.mtime", fabric)
        self.assertIn("io.timerInterrupt := timer.io.interrupt", fabric)
        self.assertNotIn("Module(new AetherAclintMtimer(", platform)

        for forbidden in (
            "val mtime = RegInit",
            "val mtimecmp = RegInit",
            "val nextMtime =",
            "val nextMtimecmp =",
            "val timerInterrupt = mtime >= mtimecmp",
        ):
            self.assertNotIn(forbidden, platform)

    def test_plic_state_is_peripheral_owned(self):
        peripheral = PLIC_PERIPHERAL.read_text(encoding="utf-8")
        fabric = SOC_FABRIC.read_text(encoding="utf-8")
        platform = SOC_PLATFORM.read_text(encoding="utf-8")

        self.assertIn("class AetherPlic(", peripheral)
        self.assertIn("private val terminalAccepted = accepted && io.complete", peripheral)
        self.assertIn("plic.io.claimRead := true.B", peripheral)
        self.assertIn("plic.io.completeWrite := true.B", peripheral)
        self.assertIn("io.interrupt := plic.io.interrupt", peripheral)

        self.assertIn("private val plic = Module(new AetherPlic(", fabric)
        self.assertIn("plic.io.request := pendingPlic", fabric)
        self.assertIn("plicComplete := responseFire", fabric)
        self.assertIn("io.supervisorExternalInterrupt := plic.io.interrupt", fabric)
        self.assertNotIn("Module(new AetherPlic(", platform)

        for forbidden in (
            "Module(new MachinePlicMmio",
            "MachinePlicMmioMap.",
            "supervisorPlic.io.",
        ):
            self.assertNotIn(forbidden, platform)

    def test_platform_fabric_owns_pma_mmio_and_interrupt_topology(self):
        fabric = SOC_FABRIC.read_text(encoding="utf-8")
        platform = SOC_PLATFORM.read_text(encoding="utf-8")
        address_map = SOC_ADDRESS_MAP.read_text(encoding="utf-8")

        self.assertIn("class AetherSoCPlatformFabric(", fabric)
        self.assertIn("val resolvedPhysicalAddress = Input(UInt(paddrBits.W))", fabric)
        self.assertIn("val resolvedAttributes = Output(new MemoryAttributes)", fabric)
        self.assertIn("private val resolvedRam =", fabric)
        self.assertIn("io.resolvedAttributes.cacheable := resolvedRam", fabric)
        self.assertIn("io.resolvedAttributes.sideEffecting := !resolvedRam", fabric)
        self.assertIn("io.resolvedAttributes.supportsAtomic := resolvedRam", fabric)

        self.assertIn("private val pendingUart =", fabric)
        self.assertIn("private val pendingExit =", fabric)
        self.assertIn("private val pendingTimer =", fabric)
        self.assertIn("private val pendingPlic =", fabric)
        self.assertIn("private val pendingMmio =", fabric)
        self.assertIn("private val pendingExternal =", fabric)
        self.assertIn("private val pendingQueue = Module(new Queue(", fabric)
        self.assertIn("assert(!(pendingMmio && pendingAtomic)", fabric)

        self.assertIn("final case class AetherSoCAddressMap(", address_map)
        self.assertIn("def qualifiedLinux(platform: PlatformConfig)", address_map)
        self.assertIn('ramBase = BigInt("80000000", 16)', address_map)
        self.assertIn('plicBase = BigInt("0c000000", 16)', address_map)

        self.assertIn("val core = Module(new AetherCoreV2Complex(", platform)
        self.assertIn("val fabric = Module(new AetherSoCPlatformFabric(", platform)
        self.assertIn("fabric.io.resolvedPhysicalAddress := core.io.resolvedPhysicalAddress", platform)
        self.assertIn("core.io.resolvedAttributes := fabric.io.resolvedAttributes", platform)
        self.assertIn("fabric.io.request <> core.io.memoryRequest", platform)
        self.assertIn("core.io.memoryResponse <> fabric.io.response", platform)

        for forbidden in (
            "private val ramBase =",
            "private val plicBase =",
            "val pendingUart =",
            "val pendingExit =",
            "val pendingTimer =",
            "val pendingPlic =",
            "Module(new AetherUart16550",
            "Module(new AetherAclintMtimer",
            "Module(new AetherPlic",
            "assert(!(pendingMmio && pendingAtomic)",
        ):
            self.assertNotIn(forbidden, platform)

        self.assertNotIn("package aethercore.sim", fabric)

    def test_sim_top_is_only_a_unified_memory_compatibility_wrapper(self):
        source = SIM_WRAPPER.read_text(encoding="utf-8")

        self.assertIn(
            "class AetherCoreV2OpenSbiRV64SimTop",
            source,
        )
        self.assertIn(
            "extends AetherCoreV2UnifiedMemoryCompatSimTop",
            source,
        )
        for forbidden in (
            "AetherCoreV2LinuxSoC",
            "private val ramBase =",
            "val supervisorPlic = Module",
            "val mtime = RegInit",
            "val dcache = Module",
        ):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
