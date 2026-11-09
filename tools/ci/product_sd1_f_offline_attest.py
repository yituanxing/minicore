#!/usr/bin/env python3
"""No-guest-clock evidence audit of five completed F RTL checkpoint artifacts.

This one-off rescue deliberately accepts an *exact known verifier typo failure*,
not arbitrary red CI, and independently checks the entire uploaded state chain.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

REPO = "yituanxing/minicore"
F_RUN = 42542974143
F_HEAD = "d07660bbaff04f19bdd57909ff27b1ab03c7dde7"
F_JOB_ID = 128921785366
MODEL_SHA = "04e671f2dfae8aa6d7c5b17a93c688cb7b30005de9a2a329f983b909f4fa1be6"
E_HOST_SHA = "74ab6999381fd71963c55e4ceff9ab233fa1428481e4670eb5d29500f4d42983"
E_RTL_SHA = "5fd39e72ef38c04a307923944df40a8286d8a0501dc8572abdffb88c4bf37220"
SD_SHA = "6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7"
FW_SHA = "4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be"
DTB_SHA = "72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c"
LINUX_SHA = "522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393"
ROOTFS_SHA = "f2f8c6bf118c0be578b633dfd5486e0d1e1ef7becdaf093bbc4f16f62d0e474e"
V2_SHA = "c47f6d16aab24bf83df9f4baab2b3cbbe19ced7bc3f42c1ffa96c5f35530aa70"
ORIGINAL_SHA = "5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc"
CHECKPOINTS = (
    (8292, 13642630450, "28316adb927a21ade2e23369d4e1388e6159370ec681e5b287eb898a493da955"),
    (8312, 13641838139, "30ae8d62b421b9b3fb4ed88cf5d9157fea719f4c0ef0ba78490c4ecd498c6c16"),
    (8332, 13642825008, "29b352e049c447588ae9a7454875b88abfdb7c139c9432e60e0a2bde89df45a2"),
    (8352, 13642178383, "4aa6e991348d6a4786e8818a01cde6aefd9ae84ad674c967a6132b0ed8ce3ad8"),
    (8372, 13642786033, "8684ccb87bc9e2ee03bcd12156679fc28b940053a05e59478652442404dd7468"),
)


def must(ok: bool, msg: str) -> None:
    if not ok:
        raise RuntimeError("F_OFFLINE_ATTEST_REJECTED: " + msg)


def api(suffix: str) -> dict:
    return json.loads(subprocess.check_output(["gh", "api", f"repos/{REPO}/{suffix}"], text=True))


def digest(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def ready_fields(b: bytes) -> dict[str, str]:
    entries = [line.split("=", 1) for line in b.decode("utf-8").splitlines()]
    must(all(len(x) == 2 for x in entries), "manifest malformed")
    keys = dict(entries)
    must(len(keys) == len(entries), "duplicate manifest key")
    return keys


def authenticate_provenance() -> None:
    run = api(f"actions/runs/{F_RUN}")
    must(
        run.get("id") == F_RUN and run.get("run_attempt") == 1
        and run.get("status") == "completed" and run.get("conclusion") == "failure"
        and run.get("head_sha") == F_HEAD and run.get("event") == "pull_request"
        and run.get("head_branch") == "design/rv64gc-product-architecture-20261103"
        and run.get("path") == ".github/workflows/product-sd1-v3-f-8272-to-8372.yml",
        "wrong F run identity/conclusion",
    )
    jobs = api(f"actions/runs/{F_RUN}/jobs?per_page=100")["jobs"]
    must(len(jobs) == 1 and jobs[0].get("id") == F_JOB_ID
         and jobs[0].get("name") == "F_real_product_rtl_five_stages"
         and jobs[0].get("status") == "completed"
         and jobs[0].get("conclusion") == "failure", "wrong F job")
    steps = jobs[0]["steps"]
    failure = [x for x in steps if x.get("conclusion") == "failure"]
    must(len(failure) == 1
         and failure[0]["name"] == "Verify five complete F checkpoints and no false Debian PASS",
         "unknown F failure beyond verifier")
    must(all(x.get("status") == "completed" and x.get("conclusion") == "success"
             for x in steps if x not in failure), "other F step not success")
    for target, _, _ in CHECKPOINTS:
        for name in (f"Advance genuine RTL Host-v3 {target-20}M to {target}M",
                     f"Upload complete {target}M before next guest RTL cycle"):
            matches = [x for x in steps if x["name"] == name and x.get("conclusion") == "success"]
            must(len(matches) == 1, "RTL advance or upload missing: " + name)
    log = subprocess.check_output(
        ["gh", "api", "--allow-escape-sequences",
         f"repos/{REPO}/actions/jobs/{F_JOB_ID}/logs"], text=True,
    )
    must("line 4: e: unbound variable" in log, "the known shell verifier typo is unproven")


def audit_all(out_dir: Path) -> dict:
    old_host, old_rtl = E_HOST_SHA, E_RTL_SHA
    results = []
    for to_m, artifact_id, zip_sha in CHECKPOINTS:
        name = f"product-sd1-migrated-v3-f-{to_m}m-{F_RUN}"
        meta = api(f"actions/artifacts/{artifact_id}")
        must(meta.get("id") == artifact_id and meta.get("workflow_run", {}).get("id") == F_RUN
             and meta.get("name") == name and meta.get("digest") == f"sha256:{zip_sha}"
             and meta.get("expired") is False and meta.get("size_in_bytes", 0) > 1000000,
             f"artifact provenance invalid at {to_m}")
        path = out_dir / f"{to_m}m.zip"
        subprocess.run([
            "curl", "-fLsS", "--retry", "2", "-H", f"Authorization: Bearer {os.environ['GH_TOKEN']}",
            f"https://api.github.com/repos/{REPO}/actions/artifacts/{artifact_id}/zip",
            "-o", str(path),
        ], check=True)
        must(digest(path.read_bytes()) == zip_sha, f"ZIP SHA mismatch at {to_m}")
        with zipfile.ZipFile(path) as z:
            ready = ready_fields(z.read(".migration-ready"))
            host = digest(z.read("persistent.pre.host"))
            rtl = digest(z.read("persistent.pre.rtl"))
            card_bytes = z.read("card-progress.json")
        card = json.loads(card_bytes)
        expected = {
            "schema": "AETSDCP1",
            "qualification": "PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS",
            "boot_cycles": str(to_m * 1000000),
            "source_cycles": str((to_m - 20) * 1000000),
            "model_sha256": MODEL_SHA, "previous_model_sha256": V2_SHA,
            "original_model_sha256": ORIGINAL_SHA,
            "firmware_sha256": FW_SHA, "dtb_sha256": DTB_SHA,
            "sd_image_sha256": SD_SHA, "linux_image_sha256": LINUX_SHA,
            "rootfs_result_sha256": ROOTFS_SHA,
            "snapshot_host_sha256": host, "snapshot_rtl_sha256": rtl,
            "source_host_sha256": old_host, "source_rtl_sha256": old_rtl,
            "card_progress_sha256": digest(card_bytes),
            "debian_login_claim": "0",
        }
        must(all(ready.get(k) == v for k, v in expected.items()), f"state SHA/identity chain mismatch at {to_m}")
        must(
            card.get("from_cycles") == (to_m - 20) * 1000000
            and card.get("to_cycles") == to_m * 1000000
            and card.get("verilator_time_after") == to_m * 1000000
            and card.get("retired_delta", 0) > 0
            and card.get("cpu_observer", {}).get("retired_delta") == card.get("retired_delta")
            and card.get("debian_login_claim") is False
            and card.get("guest_uart_login_observed") is False
            and card.get("guest_uart_service_verified") is False
            and card.get("service_login_gate_pass") is False,
            f"CPU/card clock/false-PASS state at {to_m}",
        )
        results.append({
            "cycles": to_m * 1000000, "artifact_id": artifact_id, "zip_sha256": zip_sha,
            "host_sha256": host, "rtl_sha256": rtl, "card_sha256": digest(card_bytes),
            "retired_delta": card["retired_delta"],
            "uart_bytes_delta": card["cpu_observer"]["uart_bytes_delta"],
            "sd_command_delta": card["sd_command_delta"],
        })
        old_host, old_rtl = host, rtl
        print(f"F_OFFLINE_SHA_CHAIN_PASS {to_m}M host={host} rtl={rtl} card={digest(card_bytes)}", flush=True)
    return {
        "qualification": "FIVE_REAL_PRODUCT_RTL_F_STAGES_SHA_AUDITED_KNOWN_FINAL_VERIFIER_TYPO_NOT_DEBIAN_PASS",
        "f_run": F_RUN, "f_head": F_HEAD, "f_job_id": F_JOB_ID,
        "f_run_conclusion": "failure_due_only_to_final_e_unbound_verifier",
        "only_failing_step_is_known_verifier_unbound_e": True,
        "five_advances_and_uploads_success": True, "no_guest_cycles_executed": True,
        "debian_login_claim": False, "stages": results,
    }


def main() -> None:
    if len(sys.argv) == 2 and sys.argv[1] == "--plan":
        print("PRODUCT_SD1_F_OFFLINE_ATTEST_PLAN_ONLY no_guest_cycles=1 stages=8292,8312,8332,8352,8372")
        return
    must(len(sys.argv) == 3 and sys.argv[1] == "--execute" and os.environ.get("GH_TOKEN"),
         "usage: --plan | --execute ABS_OUTPUT_DIR with GH_TOKEN")
    out = Path(sys.argv[2])
    must(out.is_absolute() and not out.exists(), "output dir must be unused and absolute")
    out.mkdir(parents=True)
    authenticate_provenance()
    manifest = audit_all(out)
    data = (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode()
    (out / "f8372-offline-attestation.json").write_bytes(data)
    (out / "f8372-offline-attestation.sha256").write_text(digest(data) + "  f8372-offline-attestation.json\n")
    print("PRODUCT_SD1_F8372_OFFLINE_ATTESTED_FIVE_REAL_RTL_STAGES_NOT_DEBIAN_PASS")


if __name__ == "__main__":
    main()
