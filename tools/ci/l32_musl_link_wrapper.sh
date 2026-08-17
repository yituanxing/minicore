#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"

BUILD_DIR="${L32_USERSPACE_BUSYBOX_BUILD_DIR}"
L32_CC="${BUILD_DIR}/l32-${L32_USERSPACE_PROFILE}-ilp32-gcc"
MUSL_PREFIX="${BUILD_DIR}/musl-prefix"
OUTPUT="${L32_USERSPACE_MUSL_WRAPPER}"

[[ -x "${L32_CC}" ]] || {
  echo "ERROR: profile compiler wrapper is missing: ${L32_CC}" >&2
  exit 20
}
[[ -d "${MUSL_PREFIX}/include" && -d "${MUSL_PREFIX}/lib" ]] || {
  echo "ERROR: qualified musl prefix is incomplete: ${MUSL_PREFIX}" >&2
  exit 21
}
for crt in crt1.o crti.o crtn.o; do
  [[ -s "${MUSL_PREFIX}/lib/${crt}" ]] || {
    echo "ERROR: qualified musl prefix is missing ${crt}" >&2
    exit 21
  }
done

mkdir -p "$(dirname "${OUTPUT}")"
cat > "${OUTPUT}.tmp.$$" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
BUILD_DIR="${L32_USERSPACE_BUSYBOX_BUILD_DIR}"
L32_CC="${BUILD_DIR}/l32-${L32_USERSPACE_PROFILE}-ilp32-gcc"
MUSL_PREFIX="${BUILD_DIR}/musl-prefix"

filtered=()
skip_next=0
for arg in "$@"; do
  if (( skip_next )); then
    skip_next=0
    continue
  fi
  case "${arg}" in
    -specs) skip_next=1 ;;
    -specs=*) ;;
    *) filtered+=("${arg}") ;;
  esac
done

compile_only=0
relocatable=0
for arg in "${filtered[@]}"; do
  case "${arg}" in
    -c|-S|-E|-M|-MM) compile_only=1 ;;
    -r|-Wl,-r|-Wl,--relocatable) relocatable=1 ;;
  esac
done

gcc_include="$("${L32_CC}" -print-file-name=include)"
common=(-nostdinc -isystem "${MUSL_PREFIX}/include" -isystem "${gcc_include}")
if (( compile_only )); then
  exec "${L32_CC}" "${common[@]}" "${filtered[@]}"
fi
if (( relocatable )); then
  exec "${L32_CC}" -nostdlib "${filtered[@]}"
fi
libgcc="$("${L32_CC}" -print-libgcc-file-name)"
exec "${L32_CC}" "${common[@]}" -nostdlib -static -L"${MUSL_PREFIX}/lib" \
  "${MUSL_PREFIX}/lib/crt1.o" "${MUSL_PREFIX}/lib/crti.o" "${filtered[@]}" \
  -Wl,--start-group -lc "${libgcc}" -Wl,--end-group "${MUSL_PREFIX}/lib/crtn.o"
EOF
mv "${OUTPUT}.tmp.$$" "${OUTPUT}"
chmod +x "${OUTPUT}"

{
  echo "L32_MUSL_LINK_WRAPPER_RESULT: status=PASS"
  echo "profile=${L32_USERSPACE_PROFILE}"
  echo "isa=${L32_USERSPACE_EFFECTIVE_ISA}"
  echo "abi=${L32_USERSPACE_ABI}"
  echo "wrapper=${OUTPUT}"
  echo "wrapper_sha256=$(sha256sum "${OUTPUT}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/musl-link-wrapper.txt"
