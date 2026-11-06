# Product G5-SD1: reviewed, zero-RTL-cycle official media seed

The explicit [official media seed workflow](../../.github/workflows/product-sd1-official-media-seed.yml)
constructs SD1's **real** Debian 13/trixie `riscv64` rootfs, 6.6.143 Linux
Image, Product 52-IRQ PLIC SPI-SD DTB, OpenSBI firmware and ext4/MBR SD
image. This is an offline *supply* job. It does **not** simulate the Product
CPU, boot Linux, reach `/dev/mmcblk0p1`, demonstrate `init/login`, or run
Debian. Guest Debian maintainer scripts still run in an ordinary, isolated
`linux/riscv64` Docker container via QEMU-user binfmt; the zero-cycle claim
refers specifically to the Product SoC/RTL, not container instructions.

## Mandatory reviewed source lock

`software/rv64/product_sd1_official_seed.lock` is the **only** source of its
five official input pins. The workflow has no override fields, unpinned
image tags, floating apt mirrors, or source substitution. Missing or malformed
pins still fail immediately, before network, toolchain installation, guest
package configuration or media generation.

The five source pins were filled on **2026-11-06 UTC** after an independent
source-only verification:

- The exact official Docker Hub `library/debian` OCI index was fetched by its
  immutable SHA256 digest. Its raw bytes matched that digest, and the index
  contained a `linux/riscv64` child. The verified digest is in the lock.
- The official `snapshot.debian.org` trixie and trixie-security `InRelease`
  files were fetched using the locked `20261105T000000Z` timestamps. Each
  signature verified against the installed Debian archive keyring, and each
  full downloaded file matched the SHA256 recorded in the lock.
- The main archive's historical signed index was dated 2026-09-12; the
  security index was dated 2026-11-04 and had a 2026-11-11 `Valid-Until`.
  This is expected for an immutable snapshot, not evidence that the package
  build has already succeeded. The seed uses `Check-Valid-Until: no` for the
  historical snapshots but still requires valid archive signatures/hashes.

This is **source-lock qualification only**: the riscv64 OCI container, apt
package graph, actual rootfs, kernel, SD image and Product RTL boot remain
unbuilt/unverified until their independent gates pass. Do not describe this
pin review as a completed Debian build or as a longrun launch.

An operator must independently select an official `docker.io/library/debian`
OCI **index** digest with a `linux/riscv64` child, two real official Debian
snapshot UTC timestamps, and exact signed `InRelease` bytes. A safe optional
candidate checker needs `docker buildx`, Python 3, `gpgv`, the previously
installed official Debian archive keyring and HTTPS read access to
`snapshot.debian.org`. For example, replacing values below with independently
chosen real pins:

```bash
debian_index=sha256:<independently-verified-official-index-digest>
docker buildx imagetools inspect --raw \
  "docker.io/library/debian@${debian_index}" > /tmp/aethercore-sd1-debian-index.raw.json

python3 tools/ci/product_sd1_official_seed_discover.py \
  --oci-digest "$debian_index" \
  --oci-index /tmp/aethercore-sd1-debian-index.raw.json \
  --main-snapshot YYYYMMDDTHHMMSSZ \
  --security-snapshot YYYYMMDDTHHMMSSZ
```

The checker verifies the **raw OCI index byte digest**, its `linux/riscv64`
child, both Debian snapshot HTTPS `InRelease` responses and GPG signatures,
then prints a candidate five-field lock to **stdout only**. It does not
install packages, change binfmt, pull rootfs layers or run guest code. Do
not use `docker manifest inspect`'s potentially reformatted JSON as the
source of a raw-byte digest. Review the candidate and commit its real pin
values to the lock. The actual official supplier repeats Debian APT GPG
signature, signed-index SHA256, image architecture and package checks before
accepting the result; the checker itself is not a release approval.

## How the offline cache becomes available

There are exactly two opt-in entries. After review and merge to the default
branch, select the *AetherCore Product SD1 Official Offline Media Seed*
`workflow_dispatch` and the `SEED_OFFICIAL_SD1_ZERO_RTL_CYCLES` confirmation.
While the full Product branch remains Draft PR #379, its same-repository owner
may instead explicitly add the `product-sd1-official-seed` PR label. The
PR-label path rejects other PR numbers, forked repositories, unexpected head
branches and bases, and checks out the event's exact head SHA. There is no
push, schedule, other-label or automatic retry trigger. Both paths run the
same zero-RTL official source/media build. The job has a hard **150
minute** timeout and individual stage timeouts: host prerequisites/toolchain,
upstream kernel baseline, official snapshot/OCI Debian rootfs, raw SD image,
Linux and OpenSBI. It refuses SD images above **2 GiB**, and does not switch
to a smaller initramfs/fake rootfs when a source fetch, QEMU binfmt, apt
configuration, kernel build or checksum fails. Cold timings cannot be
promised without access to the selected OCI and snapshot; budget for a
possible **90–150 minutes**, with the risk of a deliberate timeout failure.
Warm upstream Linux/toolchain caches may reduce this substantially, but the
official rootfs still requires full preparation on a seed miss.

External dependencies on the ephemeral Ubuntu Actions runner:

- Public Docker Hub official OCI registry, specifically immutable
  `docker.io/library/debian@sha256:...` and a riscv64 child; anonymous pulls
  can hit registry rate limits.
- Two exact HTTPS `snapshot.debian.org` timestamps with signed Debian
  `trixie`/`trixie-security` `InRelease` and matching package content;
  immutable timestamps may be missing or legitimately unavailable.
- Installed/enabled host `qemu-riscv64` binfmt for Docker's native package
  configuration; the workflow checks this and aborts if absent.
- Fixed Bootlin GCC 13.3, Linux 6.6.143 tarball, OpenSBI commit, Scala/Mill
  dependencies and Ubuntu build packages. Existing source hash checks remain
  mandatory. No extra registry credentials or externally supplied secret are
  introduced; stop if a service requires them.

After building, the workflow invokes the existing
`product_sim_sd1_manual.sh preflight`: SHA and size identities, the real SPI
card protocol and a Product-specific independent FW/DTB pair are checked
**without launching RTL**. It seals
`build/product-sd1-official-seed/seed-manifest.txt` and its retained supplier
provenance. The original required directories retain absolute path-based
build manifests. The cache payload contains ONLY these exact paths:

1. `build/rv64-debian-rootfs/result.txt`
2. `build/rv64-debian-linux/result.txt`
3. `build/rv64-debian-linux/obj/.config`
4. `build/rv64-debian-linux/obj/arch/riscv/boot/Image`
5. `build/rv64-debian-boot`
6. `build/rv64-debian-sd1-sd`
7. `build/product-sd1-official-seed`

The same exact seven paths and one `hashFiles`-locked key must appear in the
separate opt-in persistent SD1 short workflow; even the cache-path set changes
GitHub Actions cache identity. The consumer requires an **exact hit**, calls
`product_sd1_official_seed.py verify`, then the existing SD1 pin preflight.
No cold build, loose restore-prefix match, artifact download, old D3 archive,
unverified media fallback or copied host RAM/DDR snapshot may substitute.

The seed contains **no usable guest login credentials**. The original Debian
root account remains locked, and the manifest records
`console_login_credentials=NOT_PROVISIONED`. A crypt hash inside a public
Actions cache exposes an offline password-guessing surface. Actual
authenticated login provisioning needs a separately protected process,
after the media/transport/root-mount gates; getty's `login:` prompt must not
be reported as a successfully authenticated login.

This seed is `OFFLINE_READY_UNVERIFIED`, never `G5_SD1_BOOT_PASS` or a Debian
distribution qualification. Real Linux MMC enumeration, ext4 root mount,
native Debian PID1/getty/login and persistent writes across restarts still
require their own explicitly bounded and independently observed RTL/FPGA
proof.
