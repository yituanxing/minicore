"""Source-contract guard for opt-in Product SmallOoO cache capacity A/B."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOC = ROOT / "src/main/scala/aethercore/soc"


class ProductCacheCapacityAbContract(unittest.TestCase):
    def test_default_remains_frozen_512_bytes_and_explicit_variants_are_bounded(self):
        profile = (SOC / "ProductSoCCacheProfile.scala").read_text()
        self.assertIn("instructionEntries: Int = 64", profile)
        self.assertIn("dataEntries: Int = 64", profile)
        self.assertIn("Set(64, 256, 512)", profile)
        self.assertIn("productionP0: ProductSoCCacheProfile = ProductSoCCacheProfile()", profile)
        unified = (SOC / "AetherCoreProductUnifiedMemorySoC.scala").read_text()
        self.assertIn("entries = cacheProfile.instructionEntries", unified)
        self.assertIn("entries = cacheProfile.dataEntries", unified)

    def test_same_selected_profile_reaches_real_uart_ddr_spi_sd_product_top(self):
        paths = (
            "AetherCoreProductAxi4SoC.scala",
            "AetherCoreProductFpgaSoC.scala",
            "AetherCoreProductFpgaPins.scala",
            "AetherCoreProductFpgaColdBootPins.scala",
            "AetherCoreProductFpgaColdBootDdrFabric.scala",
            "AetherProductDdrFirmwareStreamLoader.scala",
            "AetherProductFpgaDdrBoardAdapters.scala",
        )
        for filename in paths:
            src = (SOC / filename).read_text()
            with self.subTest(file=filename):
                self.assertIn("val cacheProfile: ProductSoCCacheProfile", src)
                self.assertIn("cacheProfile = cacheProfile", src)
        # UnifiedMemorySoC consumes the selected profile at the actual cache
        # constructors, rather than forwarding it to another shell.
        terminal = (SOC / "AetherCoreProductUnifiedMemorySoC.scala").read_text()
        self.assertIn("val cacheProfile: ProductSoCCacheProfile", terminal)
        self.assertIn("entries = cacheProfile.instructionEntries", terminal)
        self.assertIn("entries = cacheProfile.dataEntries", terminal)
        emitter = (SOC / "ElaborateProductSoC.scala").read_text()
        self.assertIn("object ElaborateProductFpgaUartDdrSpiSd1CacheAbRv64gc", emitter)
        self.assertIn("object ElaborateProductFpgaSoCRv64gcCacheAb", emitter)
        self.assertIn("cacheProfile = ProductSoCElaboration.cacheProfile(args)", emitter)
        self.assertIn("args.drop(3)", emitter)
        # The qualified full-Debian emitter is not swapped or modified.
        self.assertIn("object ElaborateProductFpgaUartDdrSpiSd1Rv64gc", emitter)

    def test_both_cache_modules_keep_exact_8_byte_fetch_and_invalidation(self):
        icache = (SOC / "AetherSoCInstructionCache.scala").read_text()
        dcache = (ROOT / "src/main/scala/aethercore/memory/AetherDirectMappedReadCache.scala").read_text()
        self.assertIn("private val BeatBytes = dataBits / 8", icache)
        self.assertIn("val lineByteValid", icache)
        self.assertIn("io.invalidateAll", icache)
        self.assertIn("private val BeatBytes", dcache)
        self.assertIn("lineByteValid", dcache)
        self.assertIn("io.upstreamRequest", dcache)

    def test_separate_debian_short_sim_receives_profile_and_exposes_counters(self):
        sim = (ROOT / "src/main/scala/aethercore/sim/AetherCoreProductUnifiedMemoryCompatSimTop.scala").read_text()
        self.assertIn("val cacheProfile: ProductSoCCacheProfile = ProductSoCCacheProfiles.productionP0", sim)
        self.assertIn("val exportCacheCounters: Boolean = false", sim)
        self.assertIn("cacheProfile = cacheProfile", sim)
        self.assertIn("class AetherCoreProductDebianCacheAbRV64SimTop", sim)
        self.assertIn("object ElaborateProductDebianCacheAbRV64SimTop", sim)
        self.assertIn("exportCacheCounters = true", sim)
        self.assertIn("io.icacheHitCount.get := soc.io.icacheHitCount", sim)
        self.assertIn("io.icacheMissCount.get := soc.io.icacheMissCount", sim)
        # Original Debian/SD1 wrappers remain exactly the old constructor forms.
        self.assertIn("extends AetherCoreProductUnifiedMemoryCompatSimTop(debianTarget = true)", sim)
        self.assertIn("debianTarget = true, spiSdTarget = true)", sim)

        shim = (ROOT / "sim/product_rv64_opensbi_shim/VAetherCoreOpenSbiSimTop.h").read_text()
        self.assertIn("#elif defined(AETHERCORE_PRODUCT_CACHE_AB)", shim)
        self.assertIn("VAetherCoreProductDebianCacheAbRV64SimTop.h", shim)
        runner = (ROOT / "sim/opensbi_boot_main.cpp").read_text()
        self.assertIn("#ifdef AETHERCORE_PRODUCT_CACHE_AB", runner)
        self.assertIn("PRODUCT_CACHE_AB_SNAPSHOT phase=", runner)
        for field in ("ic-hits=", "ic-misses=", "dc-hits=", "dc-misses=", "dc-bypass="):
            self.assertIn(field, runner)


if __name__ == "__main__":
    unittest.main()
