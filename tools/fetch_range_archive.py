#!/usr/bin/env python3
"""Fetch a large immutable archive as independently retryable HTTP ranges."""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Iterable, Sequence

SHA512_RE = re.compile(r"\b([0-9a-fA-F]{128})\b")
CONTENT_RANGE_RE = re.compile(r"bytes\s+(\d+)-(\d+)/(\d+|\*)", re.I)


class FetchError(RuntimeError):
    pass


def run_curl(
    arguments: Sequence[str], *, capture: bool = False
) -> subprocess.CompletedProcess[bytes]:
    command = [
        "curl",
        "--fail",
        "--location",
        "--show-error",
        "--silent",
        "--http1.1",
        "--connect-timeout",
        "20",
        *arguments,
    ]
    return subprocess.run(
        command,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE,
        check=False,
    )


def fetch_checksum(url: str) -> str:
    result = run_curl(
        [
            "--max-time",
            "120",
            "--retry",
            "5",
            "--retry-delay",
            "2",
            "--retry-all-errors",
            url,
        ],
        capture=True,
    )
    if result.returncode != 0:
        raise FetchError(
            f"unable to fetch SHA-512 metadata from {url}: "
            f"{result.stderr.decode(errors='replace').strip()}"
        )
    match = SHA512_RE.search(result.stdout.decode(errors="replace"))
    if not match:
        raise FetchError(f"invalid SHA-512 metadata from {url}")
    return match.group(1).lower()


def probe_range(url: str, directory: Path) -> int:
    directory.mkdir(parents=True, exist_ok=True)
    header_path = directory / "probe.headers"
    body_path = directory / "probe.body"
    header_path.unlink(missing_ok=True)
    body_path.unlink(missing_ok=True)
    result = run_curl(
        [
            "--max-time",
            "120",
            "--retry",
            "2",
            "--retry-delay",
            "2",
            "--retry-all-errors",
            "--header",
            "Accept-Encoding: identity",
            "--range",
            "0-0",
            "--dump-header",
            str(header_path),
            "--output",
            str(body_path),
            "--write-out",
            "%{http_code}",
            url,
        ],
        capture=True,
    )
    status = result.stdout.decode(errors="replace").strip()
    headers = header_path.read_text(errors="replace") if header_path.exists() else ""
    body_size = body_path.stat().st_size if body_path.exists() else -1
    body_path.unlink(missing_ok=True)
    header_path.unlink(missing_ok=True)
    if result.returncode != 0 or status != "206" or body_size != 1:
        detail = result.stderr.decode(errors="replace").strip()
        raise FetchError(
            f"range probe failed for {url}: curl={result.returncode} "
            f"http={status or '?'} bytes={body_size} {detail}"
        )
    matches = list(CONTENT_RANGE_RE.finditer(headers))
    if not matches:
        raise FetchError(f"range probe for {url} returned no Content-Range")
    start, end, total = matches[-1].groups()
    if start != "0" or end != "0" or total == "*":
        raise FetchError(
            f"unexpected Content-Range from {url}: {matches[-1].group(0)}"
        )
    size = int(total)
    if size <= 0:
        raise FetchError(f"invalid archive size from {url}: {size}")
    return size


def select_url(urls: Iterable[str], directory: Path) -> tuple[str, int]:
    failures: list[str] = []
    for url in urls:
        try:
            return url, probe_range(url, directory)
        except FetchError as exc:
            failures.append(str(exc))
    raise FetchError("no range-capable archive URL:\n" + "\n".join(failures))


def part_path(directory: Path, index: int) -> Path:
    return directory / f"{index:08d}.part"


def download_part(
    *,
    url: str,
    directory: Path,
    index: int,
    start: int,
    end: int,
    retries: int,
    max_time: int,
) -> None:
    destination = part_path(directory, index)
    expected = end - start + 1
    if destination.exists() and destination.stat().st_size == expected:
        return
    destination.unlink(missing_ok=True)
    temporary = destination.with_suffix(".tmp")
    temporary.unlink(missing_ok=True)

    for attempt in range(1, retries + 1):
        result = run_curl(
            [
                "--max-time",
                str(max_time),
                "--speed-time",
                "60",
                "--speed-limit",
                "1024",
                "--header",
                "Accept-Encoding: identity",
                "--range",
                f"{start}-{end}",
                "--output",
                str(temporary),
                "--write-out",
                "%{http_code}",
                url,
            ],
            capture=True,
        )
        status = result.stdout.decode(errors="replace").strip()
        observed = temporary.stat().st_size if temporary.exists() else -1
        if result.returncode == 0 and status == "206" and observed == expected:
            os.replace(temporary, destination)
            return
        temporary.unlink(missing_ok=True)
        if attempt != retries:
            time.sleep(min(attempt * 2, 10))

    detail = result.stderr.decode(errors="replace").strip()
    raise FetchError(
        f"chunk {index} bytes {start}-{end} failed after {retries} attempts: "
        f"curl={result.returncode} http={status or '?'} bytes={observed} {detail}"
    )


def assemble(parts: Path, output: Path, count: int, expected_sha512: str) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.unlink(missing_ok=True)
    digest = hashlib.sha512()
    with temporary.open("wb") as destination:
        for index in range(count):
            source_path = part_path(parts, index)
            with source_path.open("rb") as source:
                while True:
                    block = source.read(1024 * 1024)
                    if not block:
                        break
                    destination.write(block)
                    digest.update(block)
        destination.flush()
        os.fsync(destination.fileno())
    observed = digest.hexdigest()
    if observed != expected_sha512:
        temporary.unlink(missing_ok=True)
        for path in parts.glob("*.part"):
            path.unlink(missing_ok=True)
        raise FetchError(
            f"SHA-512 mismatch for {output.name}: observed {observed}, "
            f"expected {expected_sha512}; discarded cached chunks"
        )
    os.replace(temporary, output)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", action="append", required=True)
    parser.add_argument("--sha512-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--chunk-size", type=int, default=2 * 1024 * 1024)
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--retries", type=int, default=5)
    parser.add_argument("--max-time", type=int, default=240)
    args = parser.parse_args(argv)
    if args.chunk_size <= 0 or args.jobs <= 0 or args.retries <= 0:
        parser.error("chunk size, jobs, and retries must be positive")
    if args.max_time <= 0:
        parser.error("max time must be positive")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    expected_sha512 = fetch_checksum(args.sha512_url)
    parts = Path(str(args.output) + ".parts")
    url, total_size = select_url(args.url, parts)
    count = (total_size + args.chunk_size - 1) // args.chunk_size
    print(
        f"range source={url} bytes={total_size} chunks={count} "
        f"chunk_size={args.chunk_size} jobs={args.jobs}",
        flush=True,
    )

    work = []
    for index in range(count):
        start = index * args.chunk_size
        end = min(total_size, start + args.chunk_size) - 1
        work.append((index, start, end))

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as executor:
        futures = [
            executor.submit(
                download_part,
                url=url,
                directory=parts,
                index=index,
                start=start,
                end=end,
                retries=args.retries,
                max_time=args.max_time,
            )
            for index, start, end in work
        ]
        completed = 0
        try:
            for future in concurrent.futures.as_completed(futures):
                future.result()
                completed += 1
                print(f"range progress={completed}/{count}", flush=True)
        except Exception:
            for future in futures:
                future.cancel()
            raise

    assemble(parts, args.output, count, expected_sha512)
    print(f"range PASS: {args.output} sha512={expected_sha512}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except FetchError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(3)
