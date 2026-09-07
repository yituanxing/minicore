#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/opensbi_first_exec.env"
source "${ROOT_DIR}/software/rv64/linux_early.env"

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision tools/ensure_riscv64_linux_gcc_13_3.sh first}"
LINUX_CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
BARE_PREFIX="${RV64_BARE_CROSS_COMPILE:-riscv64-unknown-elf-}"
ISA="rv64ima_zicsr_zifencei"
ABI="lp64"
MUSL_VERSION="1.2.5"
MUSL_ARCHIVE="https://musl.libc.org/releases/musl-1.2.5.tar.gz"
MUSL_SHA256="a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4"
BUSYBOX_VERSION="1.36.1"
BUSYBOX_ARCHIVE="https://repository.timesys.com/buildsources/b/busybox/busybox-1.36.1/busybox-1.36.1.tar.bz2"
BUSYBOX_SHA256="b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
SOURCE_DIR="${CACHE_ROOT}/rv64/linux/linux-${RV64_LINUX_VERSION}"
BASELINE_RESULT="${ROOT_DIR}/build/rv64-linux-early/result.txt"
BUILD_DIR="${ROOT_DIR}/build/rv64-busybox-shell"
OBJ_DIR="${BUILD_DIR}/obj"
USER_DIR="${BUILD_DIR}/userspace"
DOWNLOAD_DIR="${CACHE_ROOT}/rv64/userspace/downloads"
SOURCE_ROOT="${CACHE_ROOT}/rv64/userspace/sources"
MUSL_BUILD_DIR="${USER_DIR}/musl-src"
MUSL_PREFIX="${USER_DIR}/musl-prefix"
BUSYBOX_BUILD_DIR="${USER_DIR}/busybox-src"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
ROOTFS_DIR="${BUILD_DIR}/rootfs"
INIT_SCRIPT="${ROOTFS_DIR}/init"
INIT_SPEC="${ROOTFS_DIR}/initramfs.list"
JOBS="${RV64_LINUX_JOBS:-$(nproc)}"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

for tool in curl tar sha256sum file make python3 "${BARE_PREFIX}gcc" "${BARE_PREFIX}readelf" "${BARE_PREFIX}objdump"; do
  command -v "${tool}" >/dev/null 2>&1 || fail "missing required tool ${tool}"
done

grep -qx 'RV64_LINUX_EARLY_BUILD_RESULT: status=PASS' "${BASELINE_RESULT}" || fail "qualified RV64 Linux baseline missing"
BASELINE_IMAGE="$(sed -n 's/^kernel_image=//p' "${BASELINE_RESULT}" | head -n 1)"
BASELINE_OBJ="${BASELINE_IMAGE%/arch/riscv/boot/Image}"
[[ -s "${BASELINE_OBJ}/.config" && -s "${BASELINE_OBJ}/vmlinux" ]] || fail "qualified baseline object tree missing"
[[ -d "${SOURCE_DIR}" ]] || fail "qualified Linux source cache missing"

rm -rf "${BUILD_DIR}"
mkdir -p "${USER_DIR}" "${DOWNLOAD_DIR}" "${SOURCE_ROOT}" "${EVIDENCE_DIR}" "${ROOTFS_DIR}"

RV64_CC="${USER_DIR}/rv64-soft-gcc"
cat > "${RV64_CC}" <<EOF
#!/usr/bin/env bash
exec "$(command -v "${BARE_PREFIX}gcc")" -march="${ISA}" -mabi="${ABI}" -mstrict-align "\$@"
EOF
chmod +x "${RV64_CC}"

cat > "${USER_DIR}/toolchain-probe.c" <<'EOF'
#include <stdint.h>
uint64_t probe(uint64_t a, uint64_t b) { return (a * 33u) ^ b; }
EOF
"${RV64_CC}" -Os -ffreestanding -c "${USER_DIR}/toolchain-probe.c" -o "${USER_DIR}/toolchain-probe.o"
{
  echo "compiler=$("${BARE_PREFIX}gcc" --version | head -n 1)"
  echo "dumpmachine=$("${BARE_PREFIX}gcc" -dumpmachine)"
  echo "multilib=$("${BARE_PREFIX}gcc" -march="${ISA}" -mabi="${ABI}" -print-multi-directory)"
  echo "libgcc=$("${BARE_PREFIX}gcc" -march="${ISA}" -mabi="${ABI}" -print-libgcc-file-name)"
  "${BARE_PREFIX}readelf" -h -A "${USER_DIR}/toolchain-probe.o"
} | tee "${EVIDENCE_DIR}/toolchain.txt"

fetch_verified() {
  local url="$1" sha="$2" out="$3" tmp
  if [[ -s "${out}" ]] && printf '%s  %s\n' "${sha}" "${out}" | sha256sum -c - >/dev/null 2>&1; then return 0; fi
  tmp="${out}.tmp.$$"; rm -f "${tmp}"
  curl -fL --connect-timeout 15 --max-time 120 --retry 3 --retry-all-errors --retry-delay 2 "${url}" -o "${tmp}"
  printf '%s  %s\n' "${sha}" "${tmp}" | sha256sum -c -
  mv "${tmp}" "${out}"
}

MUSL_TARBALL="${DOWNLOAD_DIR}/musl-${MUSL_VERSION}.tar.gz"
BUSYBOX_TARBALL="${DOWNLOAD_DIR}/busybox-${BUSYBOX_VERSION}.tar.bz2"
fetch_verified "${MUSL_ARCHIVE}" "${MUSL_SHA256}" "${MUSL_TARBALL}"
fetch_verified "${BUSYBOX_ARCHIVE}" "${BUSYBOX_SHA256}" "${BUSYBOX_TARBALL}"

MUSL_SOURCE="${SOURCE_ROOT}/musl-${MUSL_VERSION}"
BUSYBOX_SOURCE="${SOURCE_ROOT}/busybox-${BUSYBOX_VERSION}"
if [[ ! -f "${MUSL_SOURCE}/configure" ]]; then rm -rf "${MUSL_SOURCE}"; tar -xzf "${MUSL_TARBALL}" -C "${SOURCE_ROOT}"; fi
if [[ ! -f "${BUSYBOX_SOURCE}/Makefile" ]]; then rm -rf "${BUSYBOX_SOURCE}"; tar -xjf "${BUSYBOX_TARBALL}" -C "${SOURCE_ROOT}"; fi
cp -a "${MUSL_SOURCE}" "${MUSL_BUILD_DIR}"
cp -a "${BUSYBOX_SOURCE}" "${BUSYBOX_BUILD_DIR}"

(
  cd "${MUSL_BUILD_DIR}"
  ./configure --target=riscv64-linux-musl --prefix="${MUSL_PREFIX}" --disable-shared --enable-static --enable-gcc-wrapper \
    "CC=${RV64_CC}" "CROSS_COMPILE=${BARE_PREFIX}" "CFLAGS=-Os -pipe"
  make -j"${JOBS}"
  make install
) 2>&1 | tee "${BUILD_DIR}/musl-build.log"

for crt in crt1.o crti.o crtn.o; do [[ -s "${MUSL_PREFIX}/lib/${crt}" ]] || fail "musl missing ${crt}"; done

MUSL_GCC="${USER_DIR}/rv64-musl-gcc"
cat > "${MUSL_GCC}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"
BUILD_DIR="${ROOT_DIR}/build/rv64-busybox-shell/userspace"
CC="${BUILD_DIR}/rv64-soft-gcc"
MUSL_PREFIX="${BUILD_DIR}/musl-prefix"
filtered=()
skip=0
for arg in "$@"; do
  if (( skip )); then skip=0; continue; fi
  case "$arg" in -specs) skip=1 ;; -specs=*) ;; *) filtered+=("$arg") ;; esac
done
compile=0; reloc=0
for arg in "${filtered[@]}"; do
  case "$arg" in -c|-S|-E|-M|-MM) compile=1 ;; -r|-Wl,-r|-Wl,--relocatable) reloc=1 ;; esac
done
gcc_include="$("${CC}" -print-file-name=include)"
common=(-nostdinc -isystem "${MUSL_PREFIX}/include" -isystem "${gcc_include}")
if (( compile )); then exec "${CC}" "${common[@]}" "${filtered[@]}"; fi
if (( reloc )); then exec "${CC}" -nostdlib "${filtered[@]}"; fi
libgcc="$("${CC}" -print-libgcc-file-name)"
exec "${CC}" "${common[@]}" -nostdlib -static -L"${MUSL_PREFIX}/lib" \
  "${MUSL_PREFIX}/lib/crt1.o" "${MUSL_PREFIX}/lib/crti.o" "${filtered[@]}" \
  -Wl,--start-group -lc "${libgcc}" -Wl,--end-group "${MUSL_PREFIX}/lib/crtn.o"
EOF
chmod +x "${MUSL_GCC}"

cat > "${USER_DIR}/musl-probe.c" <<'EOF'
#include <unistd.h>
int main(void) { static const char s[]="rv64 musl ok\n"; return write(1,s,sizeof(s)-1)<0; }
EOF
"${MUSL_GCC}" -Os -static "${USER_DIR}/musl-probe.c" -o "${USER_DIR}/musl-probe"
file "${USER_DIR}/musl-probe" | tee "${EVIDENCE_DIR}/musl-probe-file.txt"
"${BARE_PREFIX}readelf" -h -A "${USER_DIR}/musl-probe" | tee "${EVIDENCE_DIR}/musl-probe-readelf.txt"

(
  cd "${BUSYBOX_BUILD_DIR}"
  make ARCH=riscv allnoconfig
  python3 - <<'PY'
from pathlib import Path
p=Path(".config"); lines=p.read_text().splitlines()
def set_symbol(s, on=True):
    yes=f"{s}=y"; no=f"# {s} is not set"
    for i,line in enumerate(lines):
        if line.startswith(s+"=") or line==no:
            lines[i]=yes if on else no; return
    lines.append(yes if on else no)
for s in ("CONFIG_STATIC","CONFIG_LFS","CONFIG_ASH","CONFIG_SH_IS_ASH","CONFIG_ECHO","CONFIG_PRINTF","CONFIG_TEST","CONFIG_TRUE","CONFIG_FALSE","CONFIG_UNAME"):
    set_symbol(s, True)
set_symbol("CONFIG_SH_IS_HUSH", False)
set_symbol("CONFIG_SH_IS_NONE", False)
p.write_text("\n".join(lines)+"\n")
PY
  make ARCH=riscv oldconfig </dev/null
  make ARCH=riscv CROSS_COMPILE="${BARE_PREFIX}" CC="${MUSL_GCC}" HOSTCC="${HOSTCC:-cc}" -j"${JOBS}" busybox
) 2>&1 | tee "${BUILD_DIR}/busybox-build.log"

BUSYBOX_ELF="${BUSYBOX_BUILD_DIR}/busybox"
[[ -s "${BUSYBOX_ELF}" ]] || fail "BusyBox binary missing"
file "${BUSYBOX_ELF}" | tee "${EVIDENCE_DIR}/busybox-file.txt"
file "${BUSYBOX_ELF}" | grep -q 'statically linked' || fail "BusyBox is not static"
"${BARE_PREFIX}readelf" -h -A "${BUSYBOX_ELF}" | tee "${EVIDENCE_DIR}/busybox-readelf.txt"
busybox_arch="$("${BARE_PREFIX}readelf" -A "${BUSYBOX_ELF}" | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' | head -n1)"
[[ "${busybox_arch}" == rv64i* && "${busybox_arch}" == *"_m"* && "${busybox_arch}" == *"_a"* ]] || fail "BusyBox ISA drift: ${busybox_arch}"
if [[ "${busybox_arch}" =~ _c[0-9] || "${busybox_arch}" =~ _f[0-9] || "${busybox_arch}" =~ _d[0-9] || "${busybox_arch}" =~ _v[0-9] ]]; then
  fail "BusyBox retained unsupported C/F/D/V: ${busybox_arch}"
fi

cat > "${INIT_SCRIPT}" <<'EOF'
#!/bin/sh
/bin/uname -a
echo "RV64 BUSYBOX SHELL READY"
exec /bin/sh -i
EOF
chmod 0755 "${INIT_SCRIPT}"

cat > "${INIT_SPEC}" <<EOF
dir /bin 0755 0 0
dir /dev 0755 0 0
dir /proc 0555 0 0
dir /sys 0555 0 0
dir /tmp 1777 0 0
nod /dev/console 0600 0 0 c 5 1
nod /dev/null 0666 0 0 c 1 3
file /bin/busybox ${BUSYBOX_ELF} 0755 0 0
slink /bin/sh busybox 0777 0 0
slink /bin/uname busybox 0777 0 0
slink /bin/echo busybox 0777 0 0
slink /bin/printf busybox 0777 0 0
file /init ${INIT_SCRIPT} 0755 0 0
EOF

cp -a --reflink=auto "${BASELINE_OBJ}" "${OBJ_DIR}"
cp "${BASELINE_OBJ}/.config" "${OBJ_DIR}/.config"
"${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
  -e BLK_DEV_INITRD -d INITRAMFS_COMPRESSION_GZIP -e INITRAMFS_COMPRESSION_NONE \
  --set-str INITRAMFS_SOURCE "${INIT_SPEC}"

export KBUILD_BUILD_USER="${RV64_LINUX_BUILD_USER}"
export KBUILD_BUILD_HOST="${RV64_LINUX_BUILD_HOST}"
export KBUILD_BUILD_VERSION="${RV64_LINUX_BUILD_VERSION}"
export KBUILD_BUILD_TIMESTAMP="${RV64_LINUX_BUILD_TIMESTAMP}"
export TZ="${RV64_LINUX_BUILD_TZ}"

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" ARCH=riscv CROSS_COMPILE="${LINUX_CROSS}" olddefconfig 2>&1 | tee "${BUILD_DIR}/config.log"
grep -qx 'CONFIG_64BIT=y' "${OBJ_DIR}/.config"
grep -qx 'CONFIG_MMU=y' "${OBJ_DIR}/.config"
grep -qx 'CONFIG_BLK_DEV_INITRD=y' "${OBJ_DIR}/.config"
grep -qx 'CONFIG_INITRAMFS_COMPRESSION_NONE=y' "${OBJ_DIR}/.config"
grep -Fqx "CONFIG_INITRAMFS_SOURCE=\"${INIT_SPEC}\"" "${OBJ_DIR}/.config"
grep -qx '# CONFIG_RISCV_ISA_C is not set' "${OBJ_DIR}/.config"
grep -qx '# CONFIG_FPU is not set' "${OBJ_DIR}/.config"

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" ARCH=riscv CROSS_COMPILE="${LINUX_CROSS}" -j"${JOBS}" Image 2>&1 | tee "${BUILD_DIR}/linux-build.log"

VMLINUX="${OBJ_DIR}/vmlinux"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
[[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || fail "BusyBox initramfs Image missing"
cp "${OBJ_DIR}/.config" "${EVIDENCE_DIR}/resolved.config"
sha256sum "${BUSYBOX_ELF}" "${INIT_SCRIPT}" "${INIT_SPEC}" "${VMLINUX}" "${IMAGE}" | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "RV64_BUSYBOX_SHELL_BUILD_RESULT: status=PASS"
  echo "linux_version=${RV64_LINUX_VERSION}"
  echo "musl_version=${MUSL_VERSION}"
  echo "busybox_version=${BUSYBOX_VERSION}"
  echo "busybox_arch=${busybox_arch}"
  echo "busybox=${BUSYBOX_ELF}"
  echo "busybox_sha256=$(sha256sum "${BUSYBOX_ELF}" | awk '{print $1}')"
  echo "initramfs_spec=${INIT_SPEC}"
  echo "image=${IMAGE}"
  echo "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
