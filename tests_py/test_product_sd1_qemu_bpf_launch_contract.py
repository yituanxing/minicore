"""Source-only guardrails for supplemental QEMU-only BPF icount sampling."""

from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
LAUNCHER = ROOT / "tools/ci/product_sd1_qemu_bpf_launch.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-native-qemu-oracle.yml"


class Sd1QemuBpfLauncherContract(unittest.TestCase):
    def test_shell_and_same_qemu_native_boot_inputs(self):
        subprocess.run(["bash", "-n", str(LAUNCHER)], check=True)
        launch = LAUNCHER.read_text()
        self.assertIn("-machine virt -m 256M -smp 1", launch)
        self.assertIn("-bios default -kernel \"$LINUX_IMAGE\"", launch)
        self.assertIn("root=/dev/vda1 rootfstype=ext4 rootwait rw", launch)
        self.assertIn("init=/sbin/init systemd.unit=multi-user.target", launch)
        self.assertIn('snapshot=on', launch)
        self.assertIn('-device virtio-blk-device,drive=sd1', launch)
        self.assertIn('-serial "file:$QEMU_UART"', launch)
        self.assertIn('-S -icount shift=6,align=off,sleep=off', launch)
        self.assertIn('-gdb "unix:$GDB_SOCKET,server=on,wait=off"', launch)
        self.assertIn('-qmp "unix:$QMP_SOCKET,server=on,wait=off"', launch)
        self.assertNotIn('product_sim_sd1_manual.sh', launch)
        self.assertNotIn('product_sd1_checkpoint_relay.sh', launch)

    def test_exact_media_source_config_and_bpf_identity(self):
        launch = LAUNCHER.read_text()
        self.assertIn("source_kernel_sha256", launch)
        self.assertIn("product_config_sha256", launch)
        self.assertIn("oracle_config_sha256", launch)
        self.assertIn("oracle_image_sha256", launch)
        self.assertIn("sd_image_sha256", launch)
        self.assertIn("PRODUCT_SD1_QEMU_NATIVE_DEBIAN_INIT_LOGIN_PROMPT_PASS_NOT_RTL", launch)
        self.assertIn("afb497f885ce0b5e185a292562bc2f56c8c1a2544f82545d24a23b68c0da9d06", launch)
        self.assertIn('tools/ci/product_sd1_qemu_bpf_icount_probe.py', launch)
        self.assertIn("--icount-shift 6 --wall-seconds 420", launch)
        self.assertIn('--serial-log "$QEMU_UART"', launch)
        self.assertIn('timeout --signal=TERM --kill-after=10s 8m', launch)
        self.assertIn('trap cleanup EXIT', launch)

    def test_workflow_probes_after_native_login_independently(self):
        workflow = WORKFLOW.read_text()
        login = workflow.index("- name: Run bounded real Debian ext4 mount native init")
        probe = workflow.index("- name: Measure exact sd_bind6 BPF verifier")
        report = workflow.index("- name: Report BPF instrumentation outcome independently")
        upload = workflow.index("- name: Upload bounded log and SHA evidence")
        self.assertLess(login, probe)
        self.assertLess(probe, report)
        self.assertLess(report, upload)
        self.assertIn("id: bpf_compare", workflow)
        self.assertIn("continue-on-error: true", workflow)
        self.assertIn("steps.bpf_compare.outcome == 'failure'", workflow)
        self.assertIn('bash tools/ci/product_sd1_qemu_bpf_launch.sh', workflow)
        self.assertIn("bpf-icount-probe.json", workflow)
        self.assertIn("bpf-qemu-uart.log", workflow)
        self.assertIn("bpf-qemu-stderr.log", workflow)
        self.assertIn("non-atomic whole-boot lower bound, never RTL cycles", workflow)


if __name__ == "__main__":
    unittest.main()
