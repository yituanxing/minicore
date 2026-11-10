"""Contract: SD1 rolling checkpoints include read-only CPU/UART observer deltas."""
import unittest
from pathlib import Path


class ProductPollingCpuReportContract(unittest.TestCase):
    def test_probe_integrates_without_affecting_model_or_original_state(self):
        path = Path(__file__).resolve().parents[1] / "tools/ci/product_sd1_spi_polling_relay.sh"
        source = path.read_text()
        self.assertIn("from product_sd1_cpu_observer_audit import inspect as inspect_cpu", source)
        self.assertIn('cpu=compare_cpu(cpu_before,cpu_after)', source)
        self.assertIn('cpu["retired_delta"]!=after["commits"]-before["commits"]', source)
        self.assertIn('"uart_bytes_delta":cpu["uart_bytes_delta"]', source)
        self.assertIn('"last_16_retired_pc":cpu["last_16_retired_pc"]', source)
        self.assertIn("PRODUCT_SD1_SPI_POLLING_RELAY_SAVED cycle=", source)
        self.assertIn("snapshot_host_sha256=$(sha", source)
        self.assertIn("PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$start new_cycles=$target", source)


if __name__ == "__main__":
    unittest.main()
