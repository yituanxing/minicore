#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKSPACE_ROOT="${AETHERCORE_ZEPHYR_WORKSPACE_ROOT:-$(dirname "$ROOT")}" 
CACHE_ROOT="${AETHERCORE_ZEPHYR_CACHE_ROOT:-$HOME/.cache/aethercore/zephyr-v3.7.2}"
VENV_DIR="$CACHE_ROOT/venv-west-1.3.0"
SDK_DIR="$CACHE_ROOT/zephyr-sdk-0.16.9"
DOWNLOAD_DIR="$CACHE_ROOT/downloads"
LOG_DIR="$ROOT/build/zephyr-stage/logs"
STATE_FILE="$ROOT/build/zephyr-stage/stage-state.txt"
BUILD_DIR="$ROOT/build/zephyr-stage/host-build"
SDK_ARCHIVE="$DOWNLOAD_DIR/zephyr-sdk-0.16.9_linux-x86_64_minimal.tar.xz"
SDK_URL="https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v0.16.9/zephyr-sdk-0.16.9_linux-x86_64_minimal.tar.xz"
CURRENT_PHASE="initializing"

mkdir -p "$CACHE_ROOT" "$DOWNLOAD_DIR" "$LOG_DIR" "$(dirname "$STATE_FILE")"
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

required_commands=(git cmake ninja python3 tar xz wget)
missing_packages=()
for command in "${required_commands[@]}"; do
  if ! command -v "$command" >/dev/null 2>&1; then
    case "$command" in
      ninja) missing_packages+=(ninja-build) ;;
      xz) missing_packages+=(xz-utils) ;;
      *) missing_packages+=("$command") ;;
    esac
  fi
done
command -v dtc >/dev/null 2>&1 || missing_packages+=(device-tree-compiler)
command -v gperf >/dev/null 2>&1 || missing_packages+=(gperf)
python3 -m venv --help >/dev/null 2>&1 || missing_packages+=(python3-venv)

if (( ${#missing_packages[@]} != 0 )); then
  echo "missing_packages=${missing_packages[*]}"
  sudo -n true
  sudo -n apt-get update -y
  sudo -n apt-get install -y "${missing_packages[@]}"
fi

cmake --version | head -n 1
ninja --version
python3 --version
java -version

phase python-environment
if [[ ! -x "$VENV_DIR/bin/python" ]]; then
  python3 -m venv "$VENV_DIR"
fi
"$VENV_DIR/bin/python" -m pip install --upgrade pip wheel
"$VENV_DIR/bin/python" -m pip install "west==1.3.0"
WEST="$VENV_DIR/bin/west"
"$WEST" --version

phase west-workspace
mkdir -p "$WORKSPACE_ROOT"
manifest_rel="$(realpath --relative-to="$WORKSPACE_ROOT" "$ROOT")"
if [[ ! -d "$WORKSPACE_ROOT/.west" ]]; then
  "$WEST" init -l "$ROOT" "$WORKSPACE_ROOT"
else
  (
    cd "$WORKSPACE_ROOT"
    "$WEST" config manifest.path "$manifest_rel"
  )
fi
(
  cd "$WORKSPACE_ROOT"
  "$WEST" config manifest.group-filter -- "-hal,-tools,-bootloader,-babblesim"
  "$WEST" update -o=--depth=1 -n
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

export ZEPHYR_SDK_INSTALL_DIR="$SDK_DIR"
export PATH="$VENV_DIR/bin:$PATH"

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
