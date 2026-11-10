#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/build/rv64-debian-rootfs"
ROOTFS_DIR="${BUILD_DIR}/rootfs"
EVIDENCE_DIR="${BUILD_DIR}/evidence"

# Official Debian riscv64 OCI release, published via docker-library's
# debuerreotype pipeline. Never run an amd64 rootfs under the DUT.
IMAGE='debian:trixie-20261027-slim'
STAGE="${RV64_DEBIAN_STAGE:-d1}"
case "${STAGE}" in
  d1|d2|d3|sd1) ;;
  *) printf 'ERROR: unknown Debian qualification stage %s\n' "${STAGE}" >&2; exit 1 ;;
esac

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
for tool in readelf sudo sha256sum; do
  command -v "${tool}" >/dev/null || fail "missing host tool ${tool}"
done
if [[ "${STAGE}" != d3 && "${STAGE}" != sd1 ]]; then
  command -v docker >/dev/null || fail 'missing host tool docker'
fi

rm -rf "${BUILD_DIR}"
mkdir -p "${ROOTFS_DIR}" "${EVIDENCE_DIR}"

if [[ "${STAGE}" == d3 || "${STAGE}" == sd1 ]]; then
  # An OCI slim image does NOT contain a real distribution init or login
  # services. D3/SD1 must be supplied as independently constructed full Debian
  # riscv64 rootfs archive, pinned to an exact SHA256. A file tree from a host
  # architecture or an unpinned download must never enter the expensive RTL.
  archive="${RV64_DEBIAN_FULL_ROOTFS_ARCHIVE:-}"
  archive_sha256="${RV64_DEBIAN_FULL_ROOTFS_SHA256:-}"
  [[ -f "${archive}" && "${archive_sha256}" =~ ^[[:xdigit:]]{64}$ ]] || \
    fail "${STAGE} requires RV64_DEBIAN_FULL_ROOTFS_ARCHIVE and its exact SHA256"
  printf '%s  %s\n' "${archive_sha256}" "${archive}" | sha256sum -c - || \
    fail 'full Debian rootfs archive differs from the pinned SHA256'
  # This archive is external input and is unpacked with root privileges for
  # device nodes/ownership. Native Debian often has ABSOLUTE guest symlinks
  # (e.g. alternatives); extracting those raw on the host is unsafe. Validate
  # every path/alias first, then rewrite only these absolute guest links to
  # equivalent in-rootfs relative links in a derived, auditably hashed tar.
  # Never execute a foreign binary or dereference a guest link on the host.
  rebased_archive="${BUILD_DIR}/safe-full-rootfs.tar"
  python3 - "${archive}" "${rebased_archive}" "${STAGE}" <<'PY'
import posixpath
import sys
import tarfile

archive = sys.argv[1]
rebased_archive = sys.argv[2]
stage = sys.argv[3]
nodes = {}
seen_paths = set()

def inside(path):
    return bool(path) and path != '..' and not path.startswith('../') and not path.startswith('/')

def member_path(member):
    raw = member.name.removeprefix('./').rstrip('/')
    if raw in ('', '.'):
        return ''
    if not inside(raw) or any(part == '..' for part in raw.split('/')):
        raise ValueError(f'archive member escapes rootfs: {member.name!r}')
    # GNU tar extracts redundant separators and dot components to the same
    # filesystem entry. Reject duplicate canonical destinations *before*
    # privileged extraction so later entries cannot shadow validated links.
    path = posixpath.normpath(raw)
    if not inside(path):
        raise ValueError(f'archive member resolves outside rootfs: {member.name!r}')
    return path

try:
    with tarfile.open(archive, mode='r:*') as reader:
        for member in reader:
            path = member_path(member)
            if not path:
                continue
            if path in seen_paths:
                raise ValueError(f'duplicate normalized archive destination: {path!r}')
            seen_paths.add(path)
            if member.issym():
                dest = posixpath.normpath(
                    member.linkname.lstrip('/') if member.linkname.startswith('/')
                    else posixpath.join(posixpath.dirname(path), member.linkname))
                if not inside(dest):
                    raise ValueError(f'symlink escapes rootfs: {path!r} -> {member.linkname!r}')
                nodes[path] = dest
            elif member.islnk():
                dest = posixpath.normpath(member.linkname.lstrip('/').removeprefix('./'))
                if not inside(dest):
                    raise ValueError(f'hardlink escapes rootfs: {path!r} -> {member.linkname!r}')
                nodes[path] = dest
            elif not (member.isdir() or member.isfile() or member.ischr() or
                      member.isblk() or member.isfifo()):
                raise ValueError(f'unsupported archive member type: {path!r}')

        # Resolving each path component catches multi-level alias chains,
        # including a child subsequently created under a symlink prefix.
        for member in reader.getmembers():
            path = member_path(member)
            if not path:
                continue
            parts = path.split('/')
            resolved = []
            budget = len(nodes) + len(parts) + 1
            while parts:
                part = parts.pop(0)
                if part in ('', '.'):
                    continue
                if part == '..':
                    if not resolved:
                        raise ValueError(f'archive path leaves rootfs: {path!r}')
                    resolved.pop()
                    continue
                resolved.append(part)
                candidate = '/'.join(resolved)
                if candidate in nodes:
                    budget -= 1
                    if budget <= 0:
                        raise ValueError(f'archive link cycle: {path!r}')
                    # nodes already denotes a normalized in-rootfs target.
                    parts = nodes[candidate].split('/') + parts
                    resolved = []

        # Re-emit the original file bytes and device/owner metadata without
        # extracting anything. Only guest-root absolute symlinks are rebased;
        # this also ensures GNU tar will never create a host-absolute link.
        with tarfile.open(rebased_archive, mode='w', format=tarfile.PAX_FORMAT) as writer:
            for member in reader.getmembers():
                path = member_path(member)
                if not path:
                    continue
                member.name = path
                if member.issym() and member.linkname.startswith('/'):
                    member.linkname = posixpath.relpath(
                        member.linkname.lstrip('/'), posixpath.dirname(path) or '.')
                elif member.islnk() and member.linkname.startswith('/'):
                    member.linkname = member.linkname.lstrip('/')
                member.pax_headers = {k: v for k, v in member.pax_headers.items()
                                      if k not in ('path', 'linkpath')}
                writer.addfile(member, reader.extractfile(member) if member.isfile() else None)
except (OSError, tarfile.TarError, ValueError) as exc:
    sys.exit(f'ERROR: unsafe {stage.upper()} Debian archive: {exc}')
PY
  sha256sum "${rebased_archive}" > "${EVIDENCE_DIR}/safe-full-rootfs-tar.sha256"
  sudo tar -xf "${rebased_archive}" -C "${ROOTFS_DIR}" --numeric-owner
  actual_arch=riscv64
  image_digest="sha256:${archive_sha256}"
  source_description="full-debian-rootfs-archive:${archive}"
else
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
  source_description="official-oci:${IMAGE}"
fi

[[ -x "${ROOTFS_DIR}/bin/sh" ]] || fail 'Debian shell missing'
[[ -x "${ROOTFS_DIR}/usr/bin/dpkg" ]] || fail 'Debian dpkg missing'
[[ -r "${ROOTFS_DIR}/etc/debian_version" ]] || fail 'Debian release marker missing'

# Debian slim images omit host-managed /dev nodes. The kernel's built-in cpio
# generator preserves device special files; PID1 must open its real console.
mkdir -p "${ROOTFS_DIR}/dev" "${ROOTFS_DIR}/proc" "${ROOTFS_DIR}/sys"
# Docker export materializes its runtime /dev entries as ordinary placeholder
# files on some hosts. An initramfs needs actual character-device metadata:
# replace only these two disposable, freshly exported entries when required.
if [[ ! -c "${ROOTFS_DIR}/dev/console" ]]; then
  sudo rm -f -- "${ROOTFS_DIR}/dev/console"
  sudo mknod -m 600 "${ROOTFS_DIR}/dev/console" c 5 1
fi
if [[ ! -c "${ROOTFS_DIR}/dev/null" ]]; then
  sudo rm -f -- "${ROOTFS_DIR}/dev/null"
  sudo mknod -m 666 "${ROOTFS_DIR}/dev/null" c 1 3
fi
[[ -c "${ROOTFS_DIR}/dev/console" && -c "${ROOTFS_DIR}/dev/null" ]] || \
  fail 'Debian initramfs must contain real console/null device nodes'
case "${STAGE}" in
  d1)
    install -m 0755 "${ROOT_DIR}/software/rv64_userspace/debian_dynamic_init.sh" "${ROOTFS_DIR}/init"
    ;;
  d2)
    # Check required *distribution binaries* before spending hours in RTL.
    for program in mount mkdir echo cat uname sleep; do
      [[ -x "${ROOTFS_DIR}/bin/${program}" ]] || \
        fail "D2 requires native Debian /bin/${program}"
    done
    # Also exercise independently observable RV64GC instructions and Linux
    # facilities not necessarily selected by a short dash/coreutils path:
    # F/D arithmetic and fcsr exceptions, glibc NPTL clone/join, A-extension
    # atomic RMW, native futex and per-page memory protection. This probe is
    # linked against the fixed RV64 Linux *glibc* toolchain and remains a real
    # LP64D dynamic ELF running alongside unchanged Debian distro binaries.
    [[ -n "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-}" ]] || \
      fail 'D2 requires the pinned RV64 Linux glibc cross compiler'
    cross_gcc="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}gcc"
    [[ -x "${cross_gcc}" ]] || command -v "${cross_gcc}" >/dev/null || \
      fail "missing pinned D2 cross compiler ${cross_gcc}"
    mkdir -p "${ROOTFS_DIR}/usr/local/libexec"
    native_probe="${ROOTFS_DIR}/usr/local/libexec/aethercore-d2-native-probe"
    "${cross_gcc}" -O2 -std=gnu11 -march=rv64gc_zicsr_zifencei -mabi=lp64d \
      -frounding-math -fno-fast-math -pthread \
      "${ROOT_DIR}/software/rv64_userspace/debian_d2_native_probe.c" \
      -o "${native_probe}" -lm
    readelf -h -l -A "${native_probe}" > "${EVIDENCE_DIR}/debian-d2-native-readelf.txt"
    grep -q 'Class:[[:space:]]*ELF64' "${EVIDENCE_DIR}/debian-d2-native-readelf.txt" || \
      fail 'D2 native workload is not ELF64'
    grep -q 'Machine:[[:space:]]*RISC-V' "${EVIDENCE_DIR}/debian-d2-native-readelf.txt" || \
      fail 'D2 native workload is not RISC-V'
    grep -q 'double-float ABI' "${EVIDENCE_DIR}/debian-d2-native-readelf.txt" || \
      fail 'D2 native workload is not LP64D'
    grep -q 'Requesting program interpreter: /lib/ld-linux-riscv64-lp64d.so.1' \
      "${EVIDENCE_DIR}/debian-d2-native-readelf.txt" || \
      fail 'D2 native workload does not use the genuine LP64D glibc loader'
    install -m 0755 "${ROOT_DIR}/software/rv64_userspace/debian_d2_init.sh" "${ROOTFS_DIR}/init"
    ;;
  d3|sd1)
    # D3 executes genuine /sbin/init from its limited initramfs. SD1 puts
    # the exact same real Debian tree on an SD ext4 partition, without an
    # /init initramfs wrapper. The two runtime storage contracts are distinct.
    [[ -x "${ROOTFS_DIR}/sbin/init" ]] || fail "${STAGE} full rootfs has no native /sbin/init"
    [[ -s "${ROOTFS_DIR}/var/lib/dpkg/status" ]] || \
      fail "${STAGE} full rootfs has no installed Debian package database"
    [[ -x "${ROOTFS_DIR}/usr/sbin/agetty" || -x "${ROOTFS_DIR}/sbin/agetty" ]] || \
      fail "${STAGE} full rootfs has no native serial agetty"
    [[ -x "${ROOTFS_DIR}/usr/bin/login" || -x "${ROOTFS_DIR}/bin/login" ]] || \
      fail "${STAGE} full rootfs has no native login"
    [[ -x "${ROOTFS_DIR}/bin/mount" ]] || fail "${STAGE} full rootfs has no native mount"
    # D1's shell/dpkg identity does not establish the full distribution
    # layer. Verify the actual init/getty/login ELF binaries now, before RTL.
    for candidate in sbin/init usr/sbin/agetty usr/bin/login; do
      program="${ROOTFS_DIR}/${candidate}"
      if [[ ! -x "${program}" ]]; then
        case "${candidate}" in
          usr/sbin/agetty) program="${ROOTFS_DIR}/sbin/agetty" ;;
          usr/bin/login) program="${ROOTFS_DIR}/bin/login" ;;
        esac
      fi
      [[ -x "${program}" ]] || fail "missing ${STAGE} program ${candidate}"
      evidence_name="$(basename "${candidate}")"
      readelf -h -l -A "${program}" > "${EVIDENCE_DIR}/debian-${STAGE}-${evidence_name}-readelf.txt" || \
        fail "${STAGE} ${candidate} is not a real Debian ELF"
      grep -q 'Machine:[[:space:]]*RISC-V' \
        "${EVIDENCE_DIR}/debian-${STAGE}-${evidence_name}-readelf.txt" || \
        fail "${STAGE} ${candidate} does not contain native RISC-V code"
      grep -q 'double-float ABI' \
        "${EVIDENCE_DIR}/debian-${STAGE}-${evidence_name}-readelf.txt" || \
        fail "${STAGE} ${candidate} is not LP64D"
    done
    # Enable a real Debian serial login service without invoking foreign
    # systemctl on the CI host. Never invent a fake getty/login binary.
    # Full Debian archives are extracted with --numeric-owner by sudo, so
    # these writes must likewise run as root; the earlier unprivileged D3
    # service edits silently assumed host-writable /etc inside that archive.
    if [[ -f "${ROOTFS_DIR}/usr/lib/systemd/system/serial-getty@.service" ]]; then
      sudo mkdir -p "${ROOTFS_DIR}/etc/systemd/system/getty.target.wants"
      sudo ln -sfn /usr/lib/systemd/system/serial-getty@.service \
        "${ROOTFS_DIR}/etc/systemd/system/getty.target.wants/serial-getty@ttyS0.service"
    elif [[ -f "${ROOTFS_DIR}/lib/systemd/system/serial-getty@.service" ]]; then
      sudo mkdir -p "${ROOTFS_DIR}/etc/systemd/system/getty.target.wants"
      sudo ln -sfn /lib/systemd/system/serial-getty@.service \
        "${ROOTFS_DIR}/etc/systemd/system/getty.target.wants/serial-getty@ttyS0.service"
    elif [[ -f "${ROOTFS_DIR}/etc/inittab" ]]; then
      if ! grep -q '^T0:.*ttyS0' "${ROOTFS_DIR}/etc/inittab"; then
        printf 'T0:2345:respawn:/sbin/agetty -L 115200 ttyS0 vt100\n' | \
          sudo tee -a "${ROOTFS_DIR}/etc/inittab" >/dev/null
      fi
    else
      fail "${STAGE} rootfs has no supported native systemd/SysV serial-login service"
    fi
    if [[ "${STAGE}" == d3 ]]; then
      sudo install -m 0755 "${ROOT_DIR}/software/rv64_userspace/debian_d3_init.sh" "${ROOTFS_DIR}/init"
    else
      # An /init on the disk root would ambiguously shadow a distribution
      # initramfs and encourages accidentally shipping a D3 wrapper as PID1.
      # Only the real /sbin/init is a qualified SD1 PID1 entry point.
      [[ ! -e "${ROOTFS_DIR}/init" ]] || \
        fail 'SD1 rootfs unexpectedly contains /init; use /sbin/init directly'
      # An imported cloud/debootstrap tree may contain /dev/vda, an NFS root,
      # or another guest's /etc/fstab. Those stale dependencies can deadlock
      # systemd even when Linux successfully mounts mmcblk0p1 as /.
      # Preserve the supplier file as offline evidence, then own the exact
      # SD-root mount contract for this derived (not source) Debian image.
      [[ ! -L "${ROOTFS_DIR}/etc/fstab" ]] || \
        fail 'SD1 /etc/fstab must be a regular guest file, not a symbolic link'
      if [[ -e "${ROOTFS_DIR}/etc/fstab" ]]; then
        sudo cp -a -- "${ROOTFS_DIR}/etc/fstab" "${EVIDENCE_DIR}/supplier-fstab"
      fi
      sudo mkdir -p "${ROOTFS_DIR}/etc" "${ROOTFS_DIR}/dev/pts" \
        "${ROOTFS_DIR}/run" "${ROOTFS_DIR}/tmp"
      sudo chmod 1777 "${ROOTFS_DIR}/tmp"
      printf '%s\n' \
        '# AetherCore G5-SD1: native Debian root on external MMC SPI ext4.' \
        '/dev/mmcblk0p1 / ext4 defaults 0 1' \
        'proc /proc proc nosuid,nodev,noexec 0 0' \
        'sysfs /sys sysfs nosuid,nodev,noexec 0 0' \
        'devpts /dev/pts devpts defaults 0 0' | \
        sudo tee "${ROOTFS_DIR}/etc/fstab" >/dev/null
      # Authentic Product RTL can take longer than systemd's default 90s
      # device-job deadline to finish native udev coldplug.  The kernel has
      # already registered ttyS0 and mounted devtmpfs, yet an early
      # dev-ttyS0.device timeout permanently fails serial-getty@ttyS0.
      # Keep the stock Debian getty and udev dependency; grant the slow
      # hardware path enough guest time to publish its device unit.
      # This is a new derived SD1 media identity, NOT a change to a running
      # signed checkpoint.  Real RTL UART login still decides acceptance.
      sudo mkdir -p "${ROOTFS_DIR}/etc/systemd/system.conf.d"
      printf '%s\n' \
        '[Manager]' \
        'DefaultDeviceTimeoutSec=10min' | \
        sudo tee "${ROOTFS_DIR}/etc/systemd/system.conf.d/90-sd1-device-coldplug.conf" >/dev/null
    fi
    ;;
esac

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

# Linux 6.6 usr/gen_init_cpio embeds each regular file's *filesystem mtime*
# in the built-in cpio stream.  The OCI extraction, our generated /init, and
# the D2 native probe otherwise inherit the host runner's current time.  That
# makes the final vmlinux/Image differ between identical rebuilds, and blocks
# exact PC symbolization after an expensive RTL failure.  Normalize metadata
# only in this freshly staged, derived initramfs tree, never in the pinned
# input archive or a host directory.  -P and touch -h prevent following guest
# symlinks, including an absolute guest link such as /lib/... .  -xdev avoids
# walking an unexpected mounted filesystem below the staging root.
#
# SD1 is an ext4 disk-root image, not an embedded initramfs.  Its externally
# supplied Debian timestamps remain untouched and covered by the SD1 tree
# digest and pinned source-archive identity.
initramfs_mtime_epoch=''
if [[ "${STAGE}" != sd1 ]]; then
  source "${ROOT_DIR}/software/rv64/linux_early.env"
  initramfs_mtime_epoch="$(TZ=UTC date -u -d "${RV64_LINUX_BUILD_TIMESTAMP}" +%s)" || \
    fail 'invalid frozen Linux build timestamp for Debian initramfs'
  [[ "${initramfs_mtime_epoch}" =~ ^[[:digit:]]+$ ]] || \
    fail 'non-numeric frozen Debian initramfs timestamp'
  [[ -d "${ROOTFS_DIR}" && ! -L "${ROOTFS_DIR}" && ! -L "${BUILD_DIR}" ]] || \
    fail 'Debian initramfs staging root must be a real directory'
  sudo find -P "${ROOTFS_DIR}" -xdev -depth \
    -exec touch -h -d "@${initramfs_mtime_epoch}" -- {} +
  [[ "$(stat -c %Y -- "${ROOTFS_DIR}/init")" == "${initramfs_mtime_epoch}" ]] || \
    fail 'Debian /init timestamp normalization failed'
fi

sha256_inputs=("${ROOTFS_DIR}/bin/sh" "${ROOTFS_DIR}/usr/bin/dpkg" "${ROOTFS_DIR}/etc/debian_version")
if [[ "${STAGE}" == sd1 ]]; then
  sha256_inputs+=("${ROOTFS_DIR}/sbin/init")
else
  sha256_inputs+=("${ROOTFS_DIR}/init")
fi
sha256sum "${sha256_inputs[@]}" \
  > "${EVIDENCE_DIR}/sha256.txt"
if [[ "${STAGE}" == d2 ]]; then
  sha256sum "${ROOTFS_DIR}/usr/local/libexec/aethercore-d2-native-probe" \
    >> "${EVIDENCE_DIR}/sha256.txt"
fi

if [[ "${STAGE}" == d3 || "${STAGE}" == sd1 ]]; then
  rootfs_bytes="$(sudo du -sb "${ROOTFS_DIR}" | awk '{print $1}')"
else
  rootfs_bytes="$(du -sb "${ROOTFS_DIR}" | awk '{print $1}')"
fi
rootfs_tree_sha=''
if [[ "${STAGE}" == sd1 ]]; then
  rootfs_tree_sha="$(sudo python3 "${ROOT_DIR}/tools/ci/rv64_debian_sd1_tree_digest.py" "${ROOTFS_DIR}")"
  [[ "${rootfs_tree_sha}" =~ ^[[:xdigit:]]{64}$ ]] || \
    fail 'SD1 staged Debian directory tree digest is missing'
  printf '%s  %s\n' "${rootfs_tree_sha}" "${ROOTFS_DIR}" \
    > "${EVIDENCE_DIR}/sd1-rootfs-tree.sha256"
fi
if [[ "${STAGE}" == d3 ]]; then
  # The current G5 SoC has only 256 MiB RAM; the raw initramfs, kernel,
  # OpenSBI and Debian services are simultaneously resident. A full Debian
  # tree bigger than this conservative 96 MiB allowance is a different
  # architectural workload: requires an enlarged RAM/DTB arrangement or an
  # early block-root pivot. Abort before even compiling Linux, let alone
  # running multi-billion-cycle RTL, instead of silently exhausting memory.
  reserved_kernel_bytes=$((64 * 1024 * 1024))
  reserved_services_bytes=$((64 * 1024 * 1024))
  reserved_boot_staging_bytes=$((32 * 1024 * 1024))
  physical_ram_bytes=$((256 * 1024 * 1024))
  max_embedded_rootfs_bytes=$((physical_ram_bytes - reserved_kernel_bytes - reserved_services_bytes - reserved_boot_staging_bytes))
  {
    echo "physical_ram_bytes=${physical_ram_bytes}"
    echo "reserved_kernel_bytes=${reserved_kernel_bytes}"
    echo "reserved_services_bytes=${reserved_services_bytes}"
    echo "reserved_boot_staging_bytes=${reserved_boot_staging_bytes}"
    echo "max_embedded_rootfs_bytes=${max_embedded_rootfs_bytes}"
    echo "rootfs_bytes=${rootfs_bytes}"
  } > "${EVIDENCE_DIR}/d3-memory-budget.txt"
  [[ "${rootfs_bytes}" -le "${max_embedded_rootfs_bytes}" ]] || \
    fail "full Debian rootfs ${rootfs_bytes} B exceeds 256MiB SoC's D3 embedded-root allowance ${max_embedded_rootfs_bytes} B; enlarge RAM/DTB or switch to block-root boot before RTL"
fi
{
  echo 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS'
  echo "stage=${STAGE}"
  echo "source=${source_description}"
  echo "image=${IMAGE}"
  echo "image_digest=${image_digest}"
  echo "architecture=${actual_arch}"
  echo 'abi=lp64d'
  echo "interpreter=${interpreter}"
  echo "rootfs=${ROOTFS_DIR}"
  echo "rootfs_bytes=${rootfs_bytes}"
  if [[ "${STAGE}" != sd1 ]]; then
    echo "initramfs_mtime_epoch=${initramfs_mtime_epoch}"
  fi
  if [[ "${STAGE}" == sd1 ]]; then
    echo "rootfs_tree_sha256=${rootfs_tree_sha}"
  fi
  echo "debian_version=$(head -n 1 "${ROOTFS_DIR}/etc/debian_version")"
} | tee "${BUILD_DIR}/result.txt"
