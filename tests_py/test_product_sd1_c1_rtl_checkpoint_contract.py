"""C1 savable RTL handoff source contracts, without running expensive RTL.

These source/host checks cannot qualify a genuine Linux READY capture: only a
later bounded Product RTL run can establish its UART and quiescent clock edge.
"""

from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "sim/product_rv64_checkpoint_sd1_main.cpp"
CARD = ROOT / "sim/l32_sd_spi_card.h"


class ProductSd1C1RtlCheckpointContract(unittest.TestCase):
    def test_explicit_c1_arming_precedes_initial_guest_clock(self):
        source = HOST.read_text()
        boot = source.index('if (mode=="save" || mode=="snapshot" || c1Save) {')
        arm = source.index("if (c1Save) card.armC1RootfsBoundary();")
        initialize = source.index("aethercore::l32sim::initialize(top,mem,transport);", boot)
        first_cycle = source.index("oneCycle(top,ctx,mem,transport,card,obs,policy,false,true);", boot)
        self.assertLess(arm, initialize)
        self.assertLess(initialize, first_cycle)
        self.assertIn('AETHERCORE_SD1_C1_CONFIRM', source)
        self.assertIn('GENERIC_LINUX_ROOTFS_BOUNDARY', source)
        self.assertIn('if (c1Save && !card.c1RootfsBoundaryArmed())', source)

    def test_c1_release_checks_old_media_and_uart_before_rebind_and_go(self):
        source = HOST.read_text()
        c1 = source.index('if (c1Release) {\n      // Read BOTH complete old RTL')
        block = source[c1:source.index('obs.traceHash=kFnv;', c1)]
        self.assertLess(source.index('if (shaFile(path)!=expected)'),
                        source.index('readHost(prefix+".pre.host",id,policy'))
        self.assertLess(source.index('readHost(prefix+".pre.host",id,policy'),
                        source.index('readRtl(prefix+".pre.rtl",top)'))
        self.assertLess(block.index('requireC1Ready(obs)'),
                        block.index('card.rebindC1Rootfs(newMedia)'))
        self.assertLess(block.index('card.rebindC1Rootfs(newMedia)'),
                        block.index('obs.inputStarted=true'))
        self.assertIn('continuationId.media=newMediaSha;', block)
        self.assertIn('continuationId.rootfs=newRootfsSha;', block)
        self.assertIn('continuationPolicy.uartInput=std::string(kC1Go);', block)
        self.assertIn('obs.inputIndex!=continuationPolicy.uartInput.size()', source)
        self.assertIn('no_native_init_claim=1', source)

    def test_ready_capture_cannot_be_uart_only_or_reuse_v1_schema(self):
        source = HOST.read_text()
        card = CARD.read_text()
        self.assertIn('return card.c1RootfsHandoffQuiescent() && top.io_spiCsN', source)
        self.assertIn('!top.io_spiSclk && !transport.dataWait.active', source)
        self.assertIn('if (!c1HostQuiescent(top,transport,card))', source)
        self.assertIn('requireC1Ready(o);', source)
        self.assertIn('o.uart.find(kC1MountBegin)', source)
        self.assertIn('o.uart.find(kC1NativeExec)', source)
        self.assertIn('o.inputStarted || o.inputIndex', source)
        self.assertIn('if ((c1Advance || c1Release) && !card.c1RootfsBoundaryArmed())', source)
        self.assertIn('if (!c1RootfsHandoffQuiescent())', card)
        self.assertIn('c1ReadTracking_ && lastCsHigh_ && !lastSclk_', card)
        self.assertIn('!commandBytes_ && output_.empty()', card)
        self.assertIn('snapshot_detail::sha256(sector) != bytesSha', card)
        self.assertIn('body.u32(static_cast<std::uint32_t>(readLbas.size()))', card)
        self.assertIn('envelope.u32(c1ReadTracking_ ? 2 : 1)', card)

    def test_normal_long_relay_ceiling_and_syntax_still_qualify(self):
        source = HOST.read_text()
        self.assertIn('longRelay?4000000000ULL:1800000000ULL', source)
        self.assertIn('migratedRelay?8000000000ULL:', source)
        # Migration versions have their own raised ceilings, but C1 and the
        # zero-cycle v1 migration still cap at 4B.  Do not depend on an old
        # one-line spelling that predated the versioned migrations.
        max_cycle = source.split('const std::uint64_t maxCycle=', 1)[1].split(';', 1)[0]
        for version in range(6, 1, -1):
            self.assertIn(
                f'migratingV{version}?kV{version}MigratedMaxCycles:', max_cycle
            )
        self.assertTrue(
            max_cycle.strip().endswith(
                '(c1Mode || migrating)?4000000000ULL:defaultMaxCycle'
            ), max_cycle,
        )
        self.assertIn('(migrating ? replayCycles!=0 :', source)
        compiler = [
            "c++", "-std=c++20", "-O2", "-Wall", "-Wextra", "-Werror",
            "-fsyntax-only",
            "-I" + str(ROOT / "tools/ci/fixtures/product_checkpoint_sd1_syntax"),
            "-I" + str(ROOT / "tools/ci/fixtures/product_checkpoint_g4_syntax"),
            "-I" + str(ROOT / "sim"),
            "-DAETHERCORE_PRODUCT_DEBIAN", "-DAETHERCORE_PRODUCT_SPI_SD",
            "-DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE", str(HOST),
        ]
        result = subprocess.run(compiler, capture_output=True, text=True,
                                cwd=ROOT, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
