#!/usr/bin/env python3
"""Read-only Git branch inventory with explicit ancestry evidence.

This tool NEVER fetches, updates a ref, or deletes a branch.  ``git ls-remote``
is optional: it reports live heads without downloading their commit objects.
Consequently an unfetched head is marked UNKNOWN, not "merged" or "safe to
delete".  Use a complete local clone if an accurate ancestry inventory is
required for all remote branches.

Example: python3 tools/ci/report_branch_inventory.py --base origin/main \
    --query-remote --output-dir /tmp/aethercore-branch-inventory
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys


def git(repo: Path, *args: str, allow_missing: bool = False) -> str | None:
    process = subprocess.run(
        ["git", *args], cwd=repo, text=True, capture_output=True, check=False
    )
    if process.returncode != 0:
        if allow_missing:
            return None
        # Never echo remote URLs: Git may have credentials embedded in them.
        raise RuntimeError(f"git {args[0]} failed (exit {process.returncode})")
    return process.stdout.strip()


def local_heads(repo: Path, remote: str) -> list[dict[str, str]]:
    template = "%(refname)%09%(objectname)"
    data = git(repo, "for-each-ref", f"--format={template}",
               "refs/heads", f"refs/remotes/{remote}") or ""
    heads = []
    for line in data.splitlines():
        ref, sha = line.split("\t", 1)
        if ref.startswith("refs/heads/"):
            heads.append({"scope": "local", "name": ref.removeprefix("refs/heads/"),
                          "sha": sha})
        elif ref != f"refs/remotes/{remote}/HEAD":
            heads.append({"scope": "remote-tracking",
                          "name": ref.removeprefix(f"refs/remotes/{remote}/"), "sha": sha})
    return heads


def parse_remote_heads(raw: str) -> list[dict[str, str]]:
    heads = []
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) != 2 or not parts[1].startswith("refs/heads/"):
            raise ValueError("remote head list must contain '<SHA> refs/heads/<name>'")
        sha, ref = parts
        if len(sha) != 40 or any(c not in "0123456789abcdefABCDEF" for c in sha):
            raise ValueError("remote head list contains an invalid commit SHA")
        heads.append({"scope": "remote-live", "name": ref.removeprefix("refs/heads/"),
                      "sha": sha.lower()})
    return heads


def inspect_head(repo: Path, item: dict[str, str], base_sha: str | None) -> dict:
    sha = item["sha"]
    name = item["name"]
    category = name.split("/", 1)[0] if "/" in name else "(root)"
    result = {**item, "category": category, "date": None,
              "ahead": None, "behind": None, "merged_into_base": None,
              "state": "unknown-missing-object"}

    if git(repo, "cat-file", "-e", f"{sha}^{{commit}}", allow_missing=True) is None:
        return result
    result["date"] = git(repo, "show", "-s", "--format=%cI", sha)
    if base_sha is None:
        result["state"] = "unknown-missing-base"
        return result
    if sha == base_sha:
        result.update(state="base", ahead=0, behind=0, merged_into_base=True)
        return result
    counts = git(repo, "rev-list", "--left-right", "--count", f"{base_sha}...{sha}",
                 allow_missing=True)
    if counts is None:
        result["state"] = "unknown-ancestry"
        return result
    left, right = [int(v) for v in counts.split()]
    # Three-dot left is base-only, right is branch-only.
    result.update(ahead=right, behind=left, merged_into_base=(right == 0))
    result["state"] = ("merged" if right == 0 else
                       "ahead" if left == 0 else "diverged")
    return result


def make_inventory(repo: Path, remote: str, base: str,
                   remote_text: str | None = None) -> dict:
    if git(repo, "rev-parse", "--is-inside-work-tree") != "true":
        raise RuntimeError("the requested location is not a Git worktree")
    base_sha = git(repo, "rev-parse", "--verify", f"{base}^{{commit}}",
                   allow_missing=True)
    heads = local_heads(repo, remote)
    if remote_text is not None:
        heads.extend(parse_remote_heads(remote_text))
    inspected = [inspect_head(repo, head, base_sha) for head in
                 sorted(heads, key=lambda x: (x["scope"], x["name"]))]
    return {"generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "repository": str(repo.resolve()), "base_ref": base,
            "base_sha": base_sha, "remote_name": remote,
            "read_only": True, "heads": inspected,
            "limitations": [
                "No branch is classified safe to delete by this report.",
                "Only locally available commit objects can be compared with the base.",
                "Remote-tracking refs may be stale; remote-live requires an explicit input/query.",
            ]}


def markdown_report(data: dict) -> str:
    heads = data["heads"]
    scopes = Counter(x["scope"] for x in heads)
    states = Counter(x["state"] for x in heads)
    categories = Counter(x["category"] for x in heads if x["scope"] == "remote-live")
    lines = ["# Read-only Git branch inventory", "",
             f"Generated (UTC): {data['generated_at_utc']}",
             f"Base: `{data['base_ref']}` (`{data['base_sha'] or 'not available locally'}`)",
             "", "## Scope", "",
             "| Scope | Heads |", "| --- | ---: |"]
    lines.extend(f"| {name} | {count} |" for name, count in sorted(scopes.items()))
    lines.extend(["", "## Ancestry classification", "",
                  "| State | Heads |", "| --- | ---: |"])
    lines.extend(f"| {name} | {count} |" for name, count in sorted(states.items()))
    if categories:
        lines.extend(["", "## Live remote prefixes", "",
                      "| Prefix | Heads |", "| --- | ---: |"])
        lines.extend(f"| `{name}` | {count} |" for name, count in
                     sorted(categories.items(), key=lambda item: (-item[1], item[0])))
    lines.extend(["", "## Safety", "",
                  "**No branch has been deleted, fetched, or updated.** An unknown or",
                  "unmerged branch may contain irreplaceable code or test evidence.",
                  "This inventory never recommends automatic deletion.", ""])
    return "\n".join(lines)


def write_new(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # Never silently overwrite an existing human-reviewed inventory.
    with path.open("x", encoding="utf-8") as stream:
        stream.write(text)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--base", default="main",
                        help="local base ref/commit; missing base keeps ancestry UNKNOWN")
    parser.add_argument("--remote", default="origin")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--query-remote", action="store_true",
                        help="read live remote heads via git ls-remote (no fetch)")
    source.add_argument("--remote-heads-file", type=Path,
                        help="offline git ls-remote --heads text snapshot")
    parser.add_argument("--output-dir", type=Path,
                        help="create NEW branch-inventory.json and branch-inventory.md")
    args = parser.parse_args(argv)
    try:
        raw = None
        if args.query_remote:
            raw = git(args.repo, "ls-remote", "--heads", "--", args.remote)
        elif args.remote_heads_file is not None:
            raw = args.remote_heads_file.read_text(encoding="utf-8")
        report = make_inventory(args.repo, args.remote, args.base, raw)
        if args.output_dir is not None:
            outputs = (args.output_dir / "branch-inventory.json",
                       args.output_dir / "branch-inventory.md")
            if any(path.exists() for path in outputs):
                raise FileExistsError("inventory output already exists; choose a new directory")
            write_new(args.output_dir / "branch-inventory.json",
                      json.dumps(report, indent=2, ensure_ascii=False) + "\n")
            write_new(args.output_dir / "branch-inventory.md", markdown_report(report))
            print(f"BRANCH_INVENTORY_RESULT: heads={len(report['heads'])} "
                  f"out={args.output_dir}")
        else:
            print(markdown_report(report), end="")
    except (RuntimeError, ValueError, OSError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    sys.exit(main())
