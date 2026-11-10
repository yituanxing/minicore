#!/usr/bin/env python3
"""Emit a REVIEW-ONLY SD1 source-lock candidate; never install apt or boot.

Requires a raw OCI index obtained independently from Docker Hub at an exact
digest, plus Debian snapshot UTC timestamps. The raw index SHA is verified,
linux/riscv64 selection is checked, and gpgv verifies the two signed snapshot
InRelease bytes against a pre-installed official Debian keyring. Nothing is
written to the repository; a human must review/commit the resulting lock.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request


def discover(args: argparse.Namespace) -> str:
    digest = args.oci_digest
    if not re.fullmatch(r"sha256:[a-fA-F0-9]{64}", digest):
        raise ValueError("OCI index must be explicitly pinned as sha256:<64 hex>")
    for value in (args.main_snapshot, args.security_snapshot):
        if not re.fullmatch(r"[0-9]{8}T[0-9]{6}Z", value):
            raise ValueError("snapshot UTC must be YYYYMMDDTHHMMSSZ")
    raw = Path(args.oci_index).read_bytes()
    if hashlib.sha256(raw).hexdigest() != digest.removeprefix("sha256:").lower():
        raise ValueError("raw official OCI index bytes do not hash to the chosen digest")
    index = json.loads(raw)
    if not any(entry.get("platform", {}).get("os") == "linux" and
               entry.get("platform", {}).get("architecture") == "riscv64"
               for entry in index.get("manifests", [])):
        raise ValueError("exact official OCI index has no linux/riscv64 child")
    keyring = Path(args.keyring)
    if not keyring.is_file():
        raise ValueError(f"missing previously installed official Debian archive keyring: {keyring}")
    checksums = []
    for archive, snapshot, suite in (
        ("debian", args.main_snapshot, "trixie"),
        ("debian-security", args.security_snapshot, "trixie-security"),
    ):
        url = f"https://snapshot.debian.org/archive/{archive}/{snapshot}/dists/{suite}/InRelease"
        with urllib.request.urlopen(url, timeout=30) as response:
            if response.url.split("/", 3)[:3] != ["https:", "", "snapshot.debian.org"]:
                raise ValueError("unexpected repository redirect away from official HTTPS snapshot.debian.org")
            signed_bytes = response.read(4 * 1024 * 1024 + 1)
        if not signed_bytes or len(signed_bytes) > 4 * 1024 * 1024:
            raise ValueError(f"empty/oversized signed index: {url}")
        with tempfile.TemporaryDirectory(prefix="aether-sd1-release-") as work:
            signed_file = Path(work) / "InRelease"
            signed_file.write_bytes(signed_bytes)
            checked = subprocess.run(
                ["gpgv", "--keyring", str(keyring), str(signed_file)],
                capture_output=True, text=True, timeout=30, check=False)
            if checked.returncode:
                raise ValueError(f"Debian InRelease signature validation failed for {url}: {checked.stderr}")
        checksums.append(hashlib.sha256(signed_bytes).hexdigest())
    return "\n".join((
        "# CANDIDATE ONLY: independently review the raw docker.io/library/debian",
        "# OCI index source, snapshot dates and Debian archive key before commit.",
        "# This command downloaded signed InRelease bytes but installed no packages.",
        f"RV64_SD1_DEBIAN_OCI_DIGEST={digest.lower()}",
        f"RV64_SD1_DEBIAN_SNAPSHOT_UTC={args.main_snapshot}",
        f"RV64_SD1_DEBIAN_SECURITY_SNAPSHOT_UTC={args.security_snapshot}",
        f"RV64_SD1_DEBIAN_INRELEASE_SHA256={checksums[0]}",
        f"RV64_SD1_DEBIAN_SECURITY_INRELEASE_SHA256={checksums[1]}",
        "",
    ))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--oci-digest", required=True, help="exact official docker.io/library/debian index SHA256")
    parser.add_argument("--oci-index", required=True, help="raw bytes from docker buildx imagetools inspect --raw ...@sha256:...")
    parser.add_argument("--main-snapshot", required=True, help="signed Debian trixie snapshot UTC")
    parser.add_argument("--security-snapshot", required=True, help="signed trixie-security snapshot UTC")
    parser.add_argument("--keyring", default="/usr/share/keyrings/debian-archive-keyring.gpg")
    args = parser.parse_args()
    try:
        print(discover(args), end="")
    except (OSError, ValueError, json.JSONDecodeError, subprocess.TimeoutExpired) as error:
        sys.exit(f"ERROR: SD1 lock candidate not trusted: {error}")


if __name__ == "__main__":
    main()
