#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/build/rv64-debian-rootfs"
ROOTFS_DIR="${BUILD_DIR}/rootfs"
EVIDENCE_DIR="${BUILD_DIR}/evidence"

# Official Debian riscv64 OCI release, published via docker-library's
# debuerreotype pipeline. Never run an amd64 rootfs under the DUT.
IMAGE='debian:trixie-20261027-slim'

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
for tool in docker readelf sudo sha256sum; do
  command -v "${tool}" >/dev/null || fail "missing host tool ${tool}"
done

rm -rf "${BUILD_DIR}"
mkdir -p "${ROOTFS_DIR}" "${EVIDENCE_DIR}"

# docker create/export extracts the official riscv64 image WITHOUT executing
# foreign binaries, installing host binfmt handlers or rebuilding Debian.
docker pull --platform linux/riscv64 "${IMAGE}"
actual_arch="$(docker image inspect --format '{{.Architecture}}' "${IMAGE}")"
[[ "${actual_arch}" == riscv64 ]] || fail "image architecture is ${actual_arch}, not riscv64"
image_digest="$(docker image inspect --format '{{index .RepoDigests 0}}' "${IMAGE}")"
[[ "${image_digest}" == *@sha256:* ]] || fail 'missing OCI repository digest'
if [[ -n "${RV64_DEBIAN_EXPECTED_IMAGE_DIGEST:-}" ]]; then
  [[ "${image_digest}" == "${RV64_DEBIAN_EXPECTED_IMAGE_DIGEST}" ]] || \
    fail "official Debian image digest changed: ${image_digest}"
fi

container_id="$(docker create --platform linux/riscv64 "${IMAGE}" /bin/true)"
[[ -n "${container_id}" ]] || fail 'docker create returned no container ID'
trap 'docker rm -f "${container_id}" >/dev/null 2>&1 || true' EXIT
docker export "${container_id}" | tar -xf - --no-same-owner -C "${ROOTFS_DIR}"
docker rm "${container_id}" >/dev/null
trap - EXIT

[[ -x "${ROOTFS_DIR}/bin/sh" ]] || fail 'Debian shell missing'
[[ -x "${ROOTFS_DIR}/usr/bin/dpkg" ]] || fail 'Debian dpkg missing'
[[ -r "${ROOTFS_DIR}/etc/debian_version" ]] || fail 'Debian release marker missing'

# Debian slim images omit host-managed /dev nodes. The kernel's built-in cpio
# generator preserves device special files; PID1 must open its real console.
mkdir -p "${ROOTFS_DIR}/dev" "${ROOTFS_DIR}/proc" "${ROOTFS_DIR}/sys"
[[ -e "${ROOTFS_DIR}/dev/console" ]] || \
  sudo mknod -m 600 "${ROOTFS_DIR}/dev/console" c 5 1
[[ -e "${ROOTFS_DIR}/dev/null" ]] || \
  sudo mknod -m 666 "${ROOTFS_DIR}/dev/null" c 1 3
[[ -c "${ROOTFS_DIR}/dev/console" && -c "${ROOTFS_DIR}/dev/null" ]] || \
  fail 'Debian initramfs must contain real console/null device nodes'
install -m 0755 "${ROOT_DIR}/software/rv64_userspace/debian_dynamic_init.sh" "${ROOTFS_DIR}/init"

readelf -h -l -A "${ROOTFS_DIR}/bin/sh" > "${EVIDENCE_DIR}/debian-sh-readelf.txt"
readelf -h -l -A "${ROOTFS_DIR}/usr/bin/dpkg" > "${EVIDENCE_DIR}/debian-dpkg-readelf.txt"
grep -q 'Class:[[:space:]]*ELF64' "${EVIDENCE_DIR}/debian-sh-readelf.txt" || fail 'shell is not ELF64'
grep -q 'Machine:[[:space:]]*RISC-V' "${EVIDENCE_DIR}/debian-sh-readelf.txt" || fail 'shell is not RISC-V'
grep -q 'double-float ABI' "${EVIDENCE_DIR}/debian-sh-readelf.txt" || fail 'shell is not LP64D'
grep -q 'Machine:[[:space:]]*RISC-V' "${EVIDENCE_DIR}/debian-dpkg-readelf.txt" || fail 'dpkg is not RISC-V'
interpreter="$(sed -n 's/^.*Requesting program interpreter: \([^]]*\)].*$/\1/p' "${EVIDENCE_DIR}/debian-sh-readelf.txt" | head -n 1)"
[[ "${interpreter}" == /lib/ld-linux-riscv64-lp64d.so.1 ]] || \
  fail "unexpected Debian dynamic interpreter: ${interpreter}"
# Some distro loaders are absolute symlinks. Resolve each target inside the
# extracted rootfs, never against the host machine's /lib or /usr/lib.
loader_entry="${ROOTFS_DIR}${interpreter}"
for _ in {1..8}; do
  [[ -L "${loader_entry}" ]] || break
  link_target="$(readlink "${loader_entry}")"
  if [[ "${link_target}" == /* ]]; then
    loader_entry="${ROOTFS_DIR}${link_target}"
  else
    loader_entry="$(dirname "${loader_entry}")/${link_target}"
  fi
done
[[ -f "${loader_entry}" ]] || fail 'LP64D loader missing from extracted Debian rootfs'

sha256sum "${ROOTFS_DIR}/bin/sh" "${ROOTFS_DIR}/usr/bin/dpkg" \
  "${ROOTFS_DIR}/init" "${ROOTFS_DIR}/etc/debian_version" \
  > "${EVIDENCE_DIR}/sha256.txt"
{
  echo 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS'
  echo "image=${IMAGE}"
  echo "image_digest=${image_digest}"
  echo "architecture=${actual_arch}"
  echo 'abi=lp64d'
  echo "interpreter=${interpreter}"
  echo "rootfs=${ROOTFS_DIR}"
  echo "rootfs_bytes=$(du -sb "${ROOTFS_DIR}" | awk '{print $1}')"
  echo "debian_version=$(head -n 1 "${ROOTFS_DIR}/etc/debian_version")"
} | tee "${BUILD_DIR}/result.txt"
