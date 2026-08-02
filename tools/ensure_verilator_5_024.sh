#!/usr/bin/env bash
set -euo pipefail

version="5.024"
revision="522bead374d6b7b2adb316304126e5361b18bcf1"
cache_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
prefix="$cache_root/verilator-$version"
source_dir="$cache_root/src/verilator-$revision"
build_dir="$cache_root/build/verilator-$revision"
jobs="${AETHERCORE_TOOLCHAIN_JOBS:-4}"

activate() {
  if [[ -n "${GITHUB_PATH:-}" ]]; then
    printf '%s\n' "$prefix/bin" >> "$GITHUB_PATH"
  fi
  export PATH="$prefix/bin:$PATH"
  verilator --version
  verilator --version | grep -q "Verilator $version"
}

if [[ -x "$prefix/bin/verilator" ]] && \
   "$prefix/bin/verilator" --version | grep -q "Verilator $version"; then
  activate
  exit 0
fi

# Ubuntu 24.04 ships Verilator 5.020, which has an intermittent thread-pool
# destructor failure fixed in 5.024. Build the exact upstream fix release once
# into the persistent WSL cache; subsequent jobs only prepend its bin directory.
if ! command -v cmake >/dev/null 2>&1 || \
   ! command -v ninja >/dev/null 2>&1 || \
   ! dpkg-query -W -f='${Status}' libfl-dev 2>/dev/null | grep -q 'install ok installed'; then
  sudo -E apt-get update
  sudo -E apt-get install -y --no-install-recommends \
    cmake ninja-build libfl-dev zlib1g-dev
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

activate
