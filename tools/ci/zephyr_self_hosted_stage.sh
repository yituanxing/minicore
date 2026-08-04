#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKSPACE_ROOT="${AETHERCORE_ZEPHYR_WORKSPACE_ROOT:-$(dirname "$ROOT")}" 
CACHE_ROOT="${AETHERCORE_ZEPHYR_CACHE_ROOT:-$HOME/.cache/aethercore/zephyr-v3.7.2}"
VENV_DIR="$CACHE_ROOT/venv-west-1.3.0"
SDK_DIR="$CACHE_ROOT/zephyr-sdk-0.16.9"
HOST_TOOLS_PREFIX="$CACHE_ROOT/host-tools"
DOWNLOAD_DIR="$CACHE_ROOT/downloads"
LOG_DIR="$ROOT/build/zephyr-stage/logs"
STATE_FILE="$ROOT/build/zephyr-stage/stage-state.txt"
BUILD_DIR="$ROOT/build/zephyr-stage/host-build"
SDK_ARCHIVE="$DOWNLOAD_DIR/zephyr-sdk-0.16.9_linux-x86_64_minimal.tar.xz"
SDK_URL="https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v0.16.9/zephyr-sdk-0.16.9_linux-x86_64_minimal.tar.xz"
GET_PIP="$DOWNLOAD_DIR/get-pip.py"
GET_PIP_URL="https://bootstrap.pypa.io/get-pip.py"
GPERF_VERSION="3.1"
GPERF_ARCHIVE="$DOWNLOAD_DIR/gperf-$GPERF_VERSION.tar.gz"
GPERF_URL="https://ftp.gnu.org/gnu/gperf/gperf-$GPERF_VERSION.tar.gz"
CURRENT_PHASE="initializing"

mkdir -p "$CACHE_ROOT" "$DOWNLOAD_DIR" "$HOST_TOOLS_PREFIX" "$LOG_DIR" "$(dirname "$STATE_FILE")"
exec > >(tee "$LOG_DIR/stage.log") 2>&1

finish() {
  local rc=$?
  {
    if [[ $rc -eq 0 ]]; then
      echo "status=PASS"
    else
      echo "status=FAIL"
    fi
    echo "phase=$CURRENT_PHASE"
    echo "exit_code=$rc"
    echo "head=${GITHUB_SHA:-local}"
    echo "cache_root=$CACHE_ROOT"
    echo "workspace_root=$WORKSPACE_ROOT"
  } > "$STATE_FILE"
  cat "$STATE_FILE"
  exit "$rc"
}
trap finish EXIT

phase() {
  CURRENT_PHASE="$1"
  echo
  echo "===== phase=$CURRENT_PHASE ====="
}

phase preflight
printf 'root=%s\nworkspace=%s\ncache=%s\n' "$ROOT" "$WORKSPACE_ROOT" "$CACHE_ROOT"
printf 'proxy http=%s https=%s\n' "${http_proxy:-unset}" "${https_proxy:-unset}"
df -h "$ROOT" "$HOME"

required_commands=(git cmake ninja python3 tar xz wget make gcc java realpath file)
missing_commands=()
for command in "${required_commands[@]}"; do
  command -v "$command" >/dev/null 2>&1 || missing_commands+=("$command")
done
if (( ${#missing_commands[@]} != 0 )); then
  echo "ERROR: missing non-provisioned host commands: ${missing_commands[*]}" >&2
  exit 1
fi

cmake --version | head -n 1
ninja --version
python3 --version
java -version

phase python-environment
if [[ ! -x "$VENV_DIR/bin/python" ]]; then
  rm -rf "$VENV_DIR"
  python3 -m venv --without-pip "$VENV_DIR"
fi
if ! "$VENV_DIR/bin/python" -m pip --version >/dev/null 2>&1; then
  if ! "$VENV_DIR/bin/python" -m ensurepip --upgrade; then
    if [[ ! -s "$GET_PIP" ]]; then
      temp_get_pip="$GET_PIP.part"
      rm -f "$temp_get_pip"
      wget --tries=3 --timeout=30 --progress=dot:giga -O "$temp_get_pip" "$GET_PIP_URL"
      mv "$temp_get_pip" "$GET_PIP"
    fi
    "$VENV_DIR/bin/python" "$GET_PIP"
  fi
fi
"$VENV_DIR/bin/python" -m pip --version
"$VENV_DIR/bin/python" -m pip install --upgrade pip wheel
"$VENV_DIR/bin/python" -m pip install "west==1.3.0"
WEST="$VENV_DIR/bin/west"
"$WEST" --version

phase west-workspace
mkdir -p "$WORKSPACE_ROOT"
manifest_rel="$(realpath --relative-to="$WORKSPACE_ROOT" "$ROOT")"
if [[ ! -d "$WORKSPACE_ROOT/.west" ]]; then
  (
    cd "$WORKSPACE_ROOT"
    "$WEST" init -l "$manifest_rel"
  )
else
  (
    cd "$WORKSPACE_ROOT"
    "$WEST" config manifest.path "$manifest_rel"
  )
fi

# A cancelled shallow fetch can leave Git lock files after its process has been
# terminated. This workflow has a single concurrency slot, so no other west/git
# update can be active when the replacement run reaches this point.
if [[ -d "$WORKSPACE_ROOT/zephyr/.git" ]]; then
  for lock in shallow.lock index.lock config.lock HEAD.lock; do
    if [[ -e "$WORKSPACE_ROOT/zephyr/.git/$lock" ]]; then
      echo "removing_stale_git_lock=$WORKSPACE_ROOT/zephyr/.git/$lock"
      rm -f "$WORKSPACE_ROOT/zephyr/.git/$lock"
    fi
  done
fi

(
  cd "$WORKSPACE_ROOT"
  "$WEST" config manifest.group-filter -- "-hal,-tools,-bootloader,-babblesim"
  "$WEST" update -o=--depth=1 -n zephyr
)

test -f "$WORKSPACE_ROOT/zephyr/SDK_VERSION"
grep -Fxq '0.16.9' "$WORKSPACE_ROOT/zephyr/SDK_VERSION"
"$VENV_DIR/bin/python" -m pip install -r "$WORKSPACE_ROOT/zephyr/scripts/requirements.txt"

phase zephyr-sdk
if [[ ! -x "$SDK_DIR/setup.sh" ]]; then
  if [[ ! -s "$SDK_ARCHIVE" ]]; then
    temp_archive="$SDK_ARCHIVE.part"
    rm -f "$temp_archive"
    wget --tries=3 --timeout=30 --progress=dot:giga -O "$temp_archive" "$SDK_URL"
    mv "$temp_archive" "$SDK_ARCHIVE"
  fi
  temp_sdk="$CACHE_ROOT/zephyr-sdk-0.16.9.tmp"
  rm -rf "$temp_sdk"
  mkdir -p "$temp_sdk"
  tar -xJf "$SDK_ARCHIVE" -C "$temp_sdk" --strip-components=1
  rm -rf "$SDK_DIR"
  mv "$temp_sdk" "$SDK_DIR"
fi

if [[ ! -x "$SDK_DIR/riscv64-zephyr-elf/bin/riscv64-zephyr-elf-gcc" ]]; then
  (
    cd "$SDK_DIR"
    ./setup.sh -t riscv64-zephyr-elf
  )
fi
if [[ ! -d "$SDK_DIR/sysroots" ]]; then
  (
    cd "$SDK_DIR"
    ./setup.sh -h
  )
fi
(
  cd "$SDK_DIR"
  ./setup.sh -c
)

dtc_binary="$(find "$SDK_DIR" -type f -path '*/bin/dtc' -perm -u+x -print -quit)"
test -n "$dtc_binary"
"$dtc_binary" --version

phase user-host-tools
export PATH="$HOST_TOOLS_PREFIX/bin:$VENV_DIR/bin:$(dirname "$dtc_binary"):$PATH"
if ! command -v gperf >/dev/null 2>&1; then
  if [[ ! -s "$GPERF_ARCHIVE" ]]; then
    temp_gperf="$GPERF_ARCHIVE.part"
    rm -f "$temp_gperf"
    wget --tries=3 --timeout=30 --progress=dot:giga -O "$temp_gperf" "$GPERF_URL"
    mv "$temp_gperf" "$GPERF_ARCHIVE"
  fi
  gperf_source="$CACHE_ROOT/gperf-$GPERF_VERSION-src"
  gperf_build="$CACHE_ROOT/gperf-$GPERF_VERSION-build"
  rm -rf "$gperf_source" "$gperf_build"
  mkdir -p "$gperf_source" "$gperf_build"
  tar -xzf "$GPERF_ARCHIVE" -C "$gperf_source" --strip-components=1
  (
    cd "$gperf_build"
    "$gperf_source/configure" --prefix="$HOST_TOOLS_PREFIX"
    make -j"$(nproc)"
    make install
  )
fi
gperf --version | head -n 1

export ZEPHYR_SDK_INSTALL_DIR="$SDK_DIR"

phase static-contracts
cd "$ROOT"
python3 -m unittest discover -s tests_py -p 'test_zephyr_*.py' -v

phase chisel-compile
chmod +x mill
./mill aethercore.compile

phase zephyr-build
AETHERCORE_ZEPHYR_BUILD_DIR="$BUILD_DIR" bash tools/ci/zephyr_host_build.sh

phase evidence
file "$BUILD_DIR/zephyr/zephyr.elf"
cat "$BUILD_DIR/evidence/result.txt"
cat "$BUILD_DIR/evidence/artifacts.sha256"
