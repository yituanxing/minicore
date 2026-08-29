#!/usr/bin/env bash
set -euo pipefail

version="5.024"
revision="522bead374d6b7b2adb316304126e5361b18bcf1"
cache_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
prefix="$cache_root/verilator-$version"
source_dir="$cache_root/src/verilator-$revision"
build_dir="$cache_root/build/verilator-$revision"
marker="$prefix/.aethercore-source-revision"
jobs="${AETHERCORE_TOOLCHAIN_JOBS:-4}"
fast_verify="${AETHERCORE_VERILATOR_FAST_VERIFY:-0}"

case "$fast_verify" in
  0|1) ;;
  *)
    printf 'ERROR: AETHERCORE_VERILATOR_FAST_VERIFY must be 0 or 1, got %s\n' \
      "$fast_verify" >&2
    exit 44
    ;;
esac

ensure_runtime_assets() {
  if [[ -f "$prefix/include/verilated.mk" && \
        -x "$prefix/bin/verilator_includer" ]]; then
    return 0
  fi

  if ! command -v autoconf >/dev/null 2>&1; then
    printf 'ERROR: autoconf is required once to generate Verilator runtime makefiles.\n' >&2
    printf 'Install it from an interactive WSL shell with:\n' >&2
    printf '  sudo apt-get install -y autoconf\n' >&2
    exit 43
  fi

  # The 5.024 CMake installation is experimental and omits generated/runtime
  # files consumed by `verilator --build`. Generate the configured makefile
  # through the release's standard Autoconf path and copy the missing helper
  # script from the exact pinned source tree into the persistent prefix.
  (
    cd "$source_dir"
    autoconf
    unset VERILATOR_ROOT
    ./configure --prefix="$prefix" --disable-partial-static
  )
  install -m 0644 "$source_dir/include/verilated.mk" \
    "$prefix/include/verilated.mk"
  install -m 0644 "$source_dir/include/verilated_config.h" \
    "$prefix/include/verilated_config.h"
  install -m 0755 "$source_dir/bin/verilator_includer" \
    "$prefix/bin/verilator_includer"
}

verify_install() {
  test -x "$prefix/bin/verilator"
  test -x "$prefix/bin/verilator_bin"
  test -f "$marker"
  test "$(cat "$marker")" = "$revision"

  # When the persistent build directory is present, prove that the installed
  # executable is byte-identical to the binary built from the pinned source.
  if [[ -x "$build_dir/src/verilator_bin" ]]; then
    cmp -s "$build_dir/src/verilator_bin" "$prefix/bin/verilator_bin"
  fi

  ensure_runtime_assets
  test -f "$prefix/include/verilated.mk"
  test -x "$prefix/bin/verilator_includer"
  cmp -s "$source_dir/bin/verilator_includer" \
    "$prefix/bin/verilator_includer"

  # A fast pull-request gate already runs the real AetherCore Verilator build.
  # It needs the pinned binary/runtime identity checks above, but does not need
  # a second disposable build probe on every commit. Full/milestone gates keep
  # this independent end-to-end installation probe enabled.
  if [[ "$fast_verify" == "1" ]]; then
    return 0
  fi

  local probe_dir
  probe_dir="$(mktemp -d)"
  cat > "$probe_dir/top.sv" <<'EOF'
module top;
endmodule
EOF
  "$prefix/bin/verilator" --cc --build --top-module top \
    --Mdir "$probe_dir/obj" "$probe_dir/top.sv"
  test -f "$probe_dir/obj/Vtop__ALL.a"
  rm -rf "$probe_dir"
}

activate() {
  if [[ -n "${GITHUB_PATH:-}" ]]; then
    printf '%s\n' "$prefix/bin" >> "$GITHUB_PATH"
  fi
  export PATH="$prefix/bin:$PATH"
  printf 'aethercore_verilator_source_revision=%s\n' "$revision"
  printf 'aethercore_verilator_verify_mode=%s\n' \
    "$([[ "$fast_verify" == "1" ]] && printf fast || printf full)"
  sha256sum \
    "$prefix/bin/verilator_bin" \
    "$prefix/bin/verilator_includer" \
    "$prefix/include/verilated.mk"
  # A CMake build from a shallow release commit may print UNKNOWN.REV because
  # git-describe has no tag history. The exact source marker, executable and
  # helper identity, generated runtime assets, and full build probe are
  # authoritative.
  verilator --version || true
}

if [[ -x "$prefix/bin/verilator" && -x "$prefix/bin/verilator_bin" && \
      -f "$marker" ]] && verify_install; then
  activate
  exit 0
fi

# Recover a completed CMake install from a previous job that reached install
# successfully but stopped before writing the source-revision marker.
if [[ -x "$prefix/bin/verilator" && -x "$prefix/bin/verilator_bin" && \
      -x "$build_dir/src/verilator_bin" && -d "$source_dir/.git" ]] && \
   [[ "$(git -C "$source_dir" rev-parse HEAD)" = "$revision" ]] && \
   cmp -s "$build_dir/src/verilator_bin" "$prefix/bin/verilator_bin"; then
  printf '%s\n' "$revision" > "$marker"
  verify_install
  activate
  exit 0
fi

# Ubuntu 24.04 ships Verilator 5.020, which has an intermittent thread-pool
# destructor failure fixed in 5.024. Build the exact upstream fix release once
# into the persistent WSL cache; subsequent jobs verify and reuse it.
missing=()
command -v cmake >/dev/null 2>&1 || missing+=(cmake)
command -v ninja >/dev/null 2>&1 || missing+=(ninja-build)
dpkg-query -W -f='${Status}' libfl-dev 2>/dev/null | \
  grep -q 'install ok installed' || missing+=(libfl-dev)
dpkg-query -W -f='${Status}' zlib1g-dev 2>/dev/null | \
  grep -q 'install ok installed' || missing+=(zlib1g-dev)

if (( ${#missing[@]} != 0 )); then
  printf 'ERROR: persistent runner is missing Verilator build prerequisites:' >&2
  printf ' %s' "${missing[@]}" >&2
  printf '\nInstall them once from an interactive WSL shell with:\n' >&2
  printf '  sudo apt-get update && sudo apt-get install -y cmake ninja-build libfl-dev zlib1g-dev\n' >&2
  exit 42
fi

rm -rf "$source_dir" "$build_dir" "$prefix"
mkdir -p "$source_dir" "$build_dir" "$prefix"

git -C "$source_dir" init -q
git -C "$source_dir" remote add origin https://github.com/verilator/verilator.git
git -C "$source_dir" fetch --depth=1 origin "$revision"
git -C "$source_dir" checkout -q --detach FETCH_HEAD
test "$(git -C "$source_dir" rev-parse HEAD)" = "$revision"

cmake -S "$source_dir" -B "$build_dir" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$prefix"
cmake --build "$build_dir" --parallel "$jobs"
cmake --install "$build_dir"
cmp -s "$build_dir/src/verilator_bin" "$prefix/bin/verilator_bin"
printf '%s\n' "$revision" > "$marker"

verify_install
activate
