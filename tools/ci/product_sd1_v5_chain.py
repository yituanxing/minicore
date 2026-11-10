#!/usr/bin/env python3
"""Fail-closed, read-only checkpoint evidence and bounded V5 CI chain helpers.

This does not migrate V4 model state, advance RTL or declare Debian PASS.
Only the separately qualified V5 runner may perform genuine guest cycles.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

from product_sd1_relay_state import (
    MAGIC, VERSION, MAX_UART, FNV_OFFSET, FNV_PRIME,
    decode, read_exact, read_u64, read_string,
)


SHA = re.compile(r"[0-9a-f]{64}\Z")
NUM = re.compile(r"[0-9]+\Z")
NAME = re.compile(r"product-sd1-v5-15b-[0-9]+-attempt[0-9]+-[a-z0-9_]+-[0-9]+m\Z")
ANSI = re.compile(rb"\x1b\[[0-?]*[ -/]*[@-~]")
LOGIN_LINE = re.compile(rb"(?m)(?:^|\n)(?:[A-Za-z0-9_.-]+[ \t]+)?login:[ \t]*(?=\r?$)")
ROOT = b"VFS: Mounted root (ext4 filesystem)"
INIT = b"Run /sbin/init as init process"
SERVICE = re.compile(rb"(?m)^[^\n]*Started [^\n]*\.service[^\n]*(?:\r?$)")
FAIL = (b"Kernel panic - not syncing", b"VFS: Unable to mount root fs",
        b"VFS: Cannot open root device", b"No working init found",
        b"Failed to execute /sbin/init")


def reject(message):
    raise ValueError("PRODUCT_SD1_V5_CHAIN_REJECTED: " + message)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as src:
        for data in iter(lambda: src.read(1024 * 1024), b""):
            h.update(data)
    return h.hexdigest()


def manifest(path):
    fields = {}
    for line in Path(path).read_text().splitlines():
        if "=" not in line:
            reject("malformed migration manifest")
        k, v = line.split("=", 1)
        if k in fields or not k or not v:
            reject("duplicate or empty migration field")
        fields[k] = v
    return fields


def authentic_uart(host):
    with Path(host).open("rb") as src:
        if read_exact(src, 8) != MAGIC or read_u64(src) != VERSION:
            reject("Host schema mismatch")
        for _ in range(6):
            ident = read_string(src, 128).decode("ascii")
            if not SHA.fullmatch(ident):
                reject("invalid frozen identity")
        read_string(src, 128)
        read_string(src, 4096)
        read_u64(src)
        context_cycles = read_u64(src)
        read_u64(src)
        observer = [read_u64(src) for _ in range(15)]
        uart = read_string(src, MAX_UART)
    fnv = FNV_OFFSET
    for c in uart:
        fnv = ((fnv ^ c) * FNV_PRIME) & ((1 << 64) - 1)
    if observer[0] != context_cycles or observer[13] != fnv:
        reject("UART fingerprint or Host cycle mismatch")
    return uart


def serial_proof(uart):
    """Require ordered real serial root, native PID1, started service, login."""
    clean = ANSI.sub(b"", uart).replace(b"\r\n", b"\n")
    for fatal in FAIL:
        if fatal.lower() in clean.lower():
            reject("fatal UART text: " + fatal.decode())
    root = clean.find(ROOT)
    init = clean.find(INIT, root + len(ROOT)) if root >= 0 else -1
    service = SERVICE.search(clean, init + len(INIT)) if init >= 0 else None
    prompt = LOGIN_LINE.search(clean, service.end()) if service else None
    return {
        "schema": "PRODUCT_SD1_V5_UART_OBSERVATION_1",
        "uart_sha256": hashlib.sha256(uart).hexdigest(),
        "uart_bytes": len(uart),
        "ext4_root": root >= 0,
        "native_pid1": init >= 0,
        "started_service_after_pid1": service is not None,
        "anchored_login_after_service": prompt is not None,
        "ordered_service_login_verified": prompt is not None,
        "debian_login_claim": False,
        "qualification": "OBSERVED_GENUINE_RTL_UART_NOT_AUTOMATIC_DEBIAN_PASS",
    }


def verify_checkpoint(folder, expected_cycles=None, expected_shas=None, proof_file=False):
    folder = Path(folder)
    state = manifest(folder / ".migration-ready")
    host = folder / "persistent.pre.host"
    rtl = folder / "persistent.pre.rtl"
    card = folder / "card-progress.json"
    if not host.is_file() or not rtl.is_file() or not card.is_file():
        reject("incomplete Host/RTL/card checkpoint")
    if host.stat().st_size <= 256 * 1024 * 1024:
        reject("full DDR/SD Host checkpoint missing")
    if state.get("schema") != "AETSDCP1" or state.get("debian_login_claim") != "0":
        reject("unreviewed checkpoint claim/schema")
    cycles = state.get("boot_cycles", "")
    if not NUM.fullmatch(cycles) or int(cycles) % 20000000 != 12000000:
        reject("cycle number not in signed 20M lattice")
    prior = state.get("source_cycles", "")
    if not NUM.fullmatch(prior) or int(prior) != int(cycles) - 20000000:
        reject("checkpoint has noncontiguous 20M predecessor")
    if expected_cycles is not None and int(cycles) != expected_cycles:
        reject("checkpoint cycle not requested target")
    actual = {"host": digest(host), "rtl": digest(rtl), "card": digest(card)}
    for key, field in (("host", "snapshot_host_sha256"),
                       ("rtl", "snapshot_rtl_sha256"),
                       ("card", "card_progress_sha256")):
        if state.get(field) != actual[key]:
            reject("checkpoint " + key + " SHA drift")
    if expected_shas:
        for key, value in expected_shas.items():
            if not SHA.fullmatch(value) or actual[key] != value:
                reject("source " + key + " pin differs")
    result = decode(host)
    if result["cycles"] != int(cycles) or result["failure"]:
        reject("Host guest cycle or fatal evidence mismatch")
    if result["identity"][0] != state.get("model_sha256"):
        reject("saved model identity differs from manifest")
    uart = authentic_uart(host)
    proof = serial_proof(uart)
    proof["host_snapshot_sha256"] = actual["host"]
    proof["cycles"] = int(cycles)
    if proof_file:
        (folder / "serial-proof.json").write_text(
            json.dumps(proof, indent=2, sort_keys=True) + "\n")
    return {"cycles": int(cycles), "shas": actual, "proof": proof,
            "source_cycles": state.get("source_cycles", "")}


def verify_artifact_metadata(info, artifact_id, expected_name, expected_digest,
                             run_id, head_sha):
    if (not NUM.fullmatch(artifact_id) or not NAME.fullmatch(expected_name) or
        not SHA.fullmatch(expected_digest) or not NUM.fullmatch(run_id) or
        not re.fullmatch(r"[0-9a-f]{40}", head_sha)):
        reject("invalid artifact provenance pin syntax")
    workflow = info.get("workflow_run") or {}
    if (info.get("id") != int(artifact_id) or
        info.get("name") != expected_name or
        info.get("digest") != "sha256:" + expected_digest or
        info.get("expired") is not False or
        info.get("size_in_bytes", 0) < 1024 or
        workflow.get("id") != int(run_id) or
        workflow.get("head_sha") != head_sha):
        reject("uploaded artifact metadata identity/digest mismatch")


def verify_artifact_zip(zip_file, expected_digest):
    if not SHA.fullmatch(expected_digest) or digest(zip_file) != expected_digest:
        reject("artifact ZIP bytes do not match approved digest")


def safe_prune(previous, root, current):
    """Only prune prior *local* published checkpoint files under exact batch root."""
    root = Path(root).resolve(strict=True)
    previous = Path(previous).resolve(strict=True)
    current = Path(current).resolve(strict=True)
    if (previous == current or previous.parent != root or
        current.parent != root or not previous.name.endswith("m") or
        not current.name.endswith("m")):
        reject("unsafe local prune target")
    for name in ("persistent.pre.host", "persistent.pre.rtl"):
        candidate = previous / name
        if candidate.is_symlink() or not candidate.is_file():
            reject("unsafe prior stage file")
    # Never prune any stage before the *next* verified stage upload succeeds.
    for name in ("persistent.pre.host", "persistent.pre.rtl"):
        (previous / name).unlink()


def recover_prior_attempts(root, start_m, end_m, source, expected_shas,
                           model_sha, run_id, head_sha, job_name, token):
    """Recover *every consecutive* previously uploaded 20M stage, never guess.

    Artifacts belonging to failed attempts are immutable and already available
    before the workflow completes. An invalid/gapped artifact fails closed.
    """
    if (not token or not NUM.fullmatch(run_id) or
        not re.fullmatch(r"v5_billion_[1-5]", job_name) or
        not re.fullmatch(r"[0-9a-f]{40}", head_sha)):
        reject("unapproved rerun discovery context")
    pages = []
    page = 1
    while True:
        output = subprocess.check_output([
            "gh", "api", f"repos/yituanxing/minicore/actions/runs/{run_id}/artifacts?per_page=100&page={page}"],
            stderr=subprocess.DEVNULL, text=True)
        items = json.loads(output).get("artifacts", [])
        if not isinstance(items, list):
            reject("artifact listing schema drift")
        pages.extend(items)
        if len(items) < 100:
            break
        page += 1
        if page > 20:
            reject("artifact listing exceeds bounded pages")
    prefix = re.compile(r"product-sd1-v5-15b-" + re.escape(run_id) +
                        r"-attempt([1-9][0-9]*)-" + re.escape(job_name) +
                        r"-([0-9]+)m\Z")
    by_target = {}
    for item in pages:
        match = prefix.fullmatch(str(item.get("name", "")))
        if not match:
            continue
        target = int(match.group(2))
        if target <= start_m or target > end_m or (target - start_m) % 20:
            reject("unexpected stage from this same signed runner")
        by_target.setdefault(target, []).append((int(match.group(1)), item))
    root = Path(root)
    current_m = start_m
    last_dir = Path(source)
    last_sha = dict(expected_shas)
    last_artifact = None
    stopped = False
    for target in range(start_m + 20, end_m + 1, 20):
        if target not in by_target:
            if any(later > target for later in by_target):
                reject("artifact lineage has a missing 20M stage")
            break
        records = sorted(by_target[target], key=lambda pair: pair[0], reverse=True)
        if len(records) >= 2 and records[0][0] == records[1][0]:
            reject("duplicate artifact in one run attempt")
        _, info = records[0]
        artifact_id = str(info["id"])
        name = str(info["name"])
        archive_digest = str(info["digest"]).removeprefix("sha256:")
        verify_artifact_metadata(info, artifact_id, name, archive_digest,
                                 run_id, head_sha)
        directory = root / f"{target}m"
        if directory.exists():
            reject("rerun target directory already exists")
        with tempfile.TemporaryDirectory(prefix="v5-recover-") as temp:
            zipped = Path(temp) / "artifact.zip"
            subprocess.run(["curl", "-fsSL", "--retry", "2", "-H",
                            "Authorization: Bearer " + token,
                            f"https://api.github.com/repos/yituanxing/minicore/actions/artifacts/{artifact_id}/zip",
                            "-o", str(zipped)], check=True, stdout=subprocess.DEVNULL)
            verify_artifact_zip(zipped, archive_digest)
            with zipfile.ZipFile(zipped) as archive:
                for info_ in archive.infolist():
                    path = Path(info_.filename)
                    if (path.is_absolute() or ".." in path.parts or not path.parts or
                        (info_.external_attr >> 16) & 0o170000 == 0o120000):
                        reject("unsafe staged artifact ZIP path/symlink")
                directory.mkdir(parents=True)
                archive.extractall(directory)
        result = verify_checkpoint(directory, target * 1000000, proof_file=True)
        ready = manifest(directory / ".migration-ready")
        if (ready.get("source_cycles") != str(current_m * 1000000) or
            ready.get("source_host_sha256") != last_sha["host"] or
            ready.get("source_rtl_sha256") != last_sha["rtl"] or
            ready.get("model_sha256") != model_sha):
            reject("retrieved stage does not chain to exact predecessor")
        if last_dir.parent == root:
            safe_prune(last_dir, root, directory)
        current_m, last_dir, last_sha = target, directory, result["shas"]
        last_artifact = (artifact_id, archive_digest, name)
        stopped = bool(result["proof"]["ordered_service_login_verified"])
        if stopped:
            if any(later > target for later in by_target):
                reject("observed real login yet later guest cycles uploaded")
            break
    return {"current_m": current_m, "source_dir": str(last_dir),
            "last_sha": last_sha, "last_artifact": last_artifact,
            "stopped": stopped, "recovered_stages": (current_m - start_m) // 20}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("verify-stage")
    v.add_argument("directory", type=Path)
    v.add_argument("cycles", type=int)
    a = sub.add_parser("verify-artifact")
    for key in ("metadata", "id", "name", "digest", "run_id", "head_sha"):
        a.add_argument(key)
    z = sub.add_parser("verify-zip")
    z.add_argument("zip_file")
    z.add_argument("digest")
    p = sub.add_parser("prune")
    p.add_argument("previous")
    p.add_argument("batch_root")
    p.add_argument("current")
    rec = sub.add_parser("recover")
    rec.add_argument("root")
    rec.add_argument("start_m", type=int)
    rec.add_argument("end_m", type=int)
    rec.add_argument("source")
    for key in ("host", "rtl", "card", "model", "run_id", "head_sha", "job_name"):
        rec.add_argument(key)
    args = ap.parse_args()
    try:
        if args.cmd == "verify-stage":
            out = verify_checkpoint(args.directory, args.cycles, proof_file=True)
            print(json.dumps(out, sort_keys=True))
        elif args.cmd == "verify-artifact":
            verify_artifact_metadata(json.loads(Path(args.metadata).read_text()),
                                     args.id, args.name, args.digest,
                                     args.run_id, args.head_sha)
            print("PRODUCT_SD1_V5_ARTIFACT_METADATA_AUTHENTICATED")
        elif args.cmd == "verify-zip":
            verify_artifact_zip(args.zip_file, args.digest)
            print("PRODUCT_SD1_V5_ARTIFACT_ZIP_SHA_AUTHENTICATED")
        elif args.cmd == "prune":
            safe_prune(args.previous, args.batch_root, args.current)
            print("PRODUCT_SD1_V5_LOCAL_PRIOR_STAGE_PRUNED_AFTER_UPLOAD")
        elif args.cmd == "recover":
            print(json.dumps(recover_prior_attempts(
                args.root, args.start_m, args.end_m, args.source,
                {"host": args.host, "rtl": args.rtl, "card": args.card},
                args.model, args.run_id, args.head_sha, args.job_name,
                os.environ.get("GH_TOKEN", "")), sort_keys=True))
    except (ValueError, OSError, UnicodeError, json.JSONDecodeError) as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(16) from exc


if __name__ == "__main__":
    main()
