from __future__ import annotations

import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "fetch_range_archive.py"
CHUNK_SIZE = 256 * 1024


class RangeHandler(BaseHTTPRequestHandler):
    payload = b""
    checksum = ""
    fail_once = {2}
    requests: dict[int, int] = {}

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_GET(self) -> None:
        if self.path == "/archive.sha512":
            body = f"{self.checksum}  archive.bin\n".encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path != "/archive.bin":
            self.send_error(404)
            return

        value = self.headers.get("Range")
        if not value or not value.startswith("bytes="):
            self.send_response(200)
            self.send_header("Content-Length", str(len(self.payload)))
            self.end_headers()
            self.wfile.write(self.payload)
            return

        lower, upper = value[6:].split("-", 1)
        start = int(lower)
        end = int(upper)
        index = start // CHUNK_SIZE
        count = self.requests.get(index, 0)
        self.requests[index] = count + 1

        if index in self.fail_once and count == 0 and start != 0:
            body = self.payload[start : min(end + 1, start + 100)]
            self.send_response(206)
            self.send_header(
                "Content-Range", f"bytes {start}-{end}/{len(self.payload)}"
            )
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
            return

        body = self.payload[start : end + 1]
        self.send_response(206)
        self.send_header("Content-Range", f"bytes {start}-{end}/{len(self.payload)}")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class FetchRangeArchiveTest(unittest.TestCase):
    def test_parallel_retry_resume_and_checksum(self) -> None:
        payload = bytes(
            (index * 17 + 3) % 256 for index in range(3 * 1024 * 1024 + 123)
        )
        RangeHandler.payload = payload
        RangeHandler.checksum = hashlib.sha512(payload).hexdigest()
        RangeHandler.requests = {}
        server = ThreadingHTTPServer(("127.0.0.1", 0), RangeHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "archive.bin"
                base = f"http://127.0.0.1:{server.server_port}"
                result = subprocess.run(
                    [
                        sys.executable,
                        str(SCRIPT),
                        "--url",
                        f"{base}/archive.bin",
                        "--sha512-url",
                        f"{base}/archive.sha512",
                        "--output",
                        str(output),
                        "--chunk-size",
                        str(CHUNK_SIZE),
                        "--jobs",
                        "4",
                        "--retries",
                        "3",
                        "--max-time",
                        "10",
                    ],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    timeout=30,
                )
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                self.assertEqual(output.read_bytes(), payload)
                self.assertIn(b"range PASS", result.stdout)
                self.assertGreaterEqual(RangeHandler.requests.get(2, 0), 2)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
