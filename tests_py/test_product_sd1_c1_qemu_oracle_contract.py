"""Fast C1 QEMU handoff checks; intentionally no guest boot or Product RTL."""

import importlib.util
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
WATCH = ROOT / "tools/ci/product_sd1_c1_qemu_watch.py"
SCRIPT = ROOT / "tools/ci/product_sd1_c1_qemu_oracle.sh"
spec = importlib.util.spec_from_file_location("c1_qemu_watch", WATCH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class C1QemuOracleContract(unittest.TestCase):
    def test_serial_connection_contract_without_host_socket_permission(self):
        serial = mock.Mock()
        qemu = mock.Mock()
        qemu.poll.return_value = None
        path = Path("/tmp/aethercore-c1-qemu-test.sock")
        with mock.patch.object(module.socket, "socket", return_value=serial) as create:
            self.assertIs(module.connect_serial(path, qemu, timeout=1), serial)
        create.assert_called_once_with(socket.AF_UNIX, socket.SOCK_STREAM)
        serial.connect.assert_called_once_with(str(path))
        serial.setblocking.assert_called_once_with(False)

    def test_reliable_bidirectional_qemu_serial_transport(self):
        source = WATCH.read_text()
        self.assertIn('f"unix:{serial_path},server=on,wait=on"', source)
        self.assertIn("serial.sendall(RELEASE)", source)
        self.assertNotIn("stdin=subprocess.PIPE", source)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "serial.sock"
            try:
                server_socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            except PermissionError as error:
                # Restricted local runners can prohibit AF_UNIX entirely;
                # GitHub Actions still exercises the actual guest handoff.
                self.skipTest(f"AF_UNIX blocked by environment: {error}")
            with server_socket as server:
                server.bind(str(path))
                server.listen(1)
                fake_qemu = mock.Mock()
                fake_qemu.poll.return_value = None
                with module.connect_serial(path, fake_qemu, timeout=2) as host:
                    with server.accept()[0] as guest:
                        host.sendall(module.RELEASE)
                        self.assertEqual(guest.recv(64), module.RELEASE)

    def test_gated_native_init_sequence_accepts_split_uart(self):
        proof = module.C1Proof()
        proof.observe(b"OpenSBI\r\nAETHERCORE_C1_REA")
        self.assertFalse(proof.ready)
        proof.observe(b"DY rootfs_unmounted=1\n")
        self.assertTrue(proof.ready)
        self.assertFalse(proof.mount_started)
        proof.go_sent = True
        for part in (module.MOUNT_BEGIN, module.MOUNT_OK,
                     module.NATIVE_EXEC, b"debian login:"):
            proof.observe(part)
        self.assertIsNone(proof.failure)
        self.assertTrue(proof.login)

    def test_pre_go_rootfs_mount_rejected(self):
        proof = module.C1Proof()
        proof.observe(module.READY)
        proof.observe(module.MOUNT_BEGIN)
        self.assertIn("before host C1 GO", proof.failure)

    def test_reversed_mount_exec_and_guest_panic_rejected(self):
        proof = module.C1Proof()
        proof.observe(module.READY)
        proof.go_sent = True
        proof.observe(module.NATIVE_EXEC)
        self.assertIn("before ext4 mount", proof.failure)
        proof = module.C1Proof()
        proof.observe(module.READY)
        proof.observe(b"AETHERCORE_C1_FAIL stage=ext4_mount errno=22\n")
        self.assertIsNotNone(proof.failure)

    def test_qemu_profile_is_separate_from_product_spi_and_rootfs_embedded_init(self):
        text = SCRIPT.read_text()
        self.assertIn('cp -a --reflink=auto "$C1_DIR/obj"', text)
        self.assertIn('CONFIG_INITRAMFS_SOURCE=', text)
        self.assertIn('rootfs_binding=NONE', text)
        self.assertIn('QUALIFIED', text)
        self.assertIn('media_sha256', text)
        self.assertIn('QEMU_VIRTIO_C1_ONLY_NOT_PRODUCT_SPI_RTL', text)
        self.assertNotIn('product_sim_sd1_manual.sh longrun', text)
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)
        subprocess.run(["python3", "-m", "py_compile", str(WATCH)], check=True)


if __name__ == "__main__":
    unittest.main()
