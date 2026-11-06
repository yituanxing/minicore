#!/usr/bin/env bash
# Build a genuine, configured Debian 13/trixie riscv64 root filesystem OFF the
# FPGA/simulator.  Guest programs run ONLY inside an ordinary Docker container
# with explicitly selected linux/riscv64 platform (typically via QEMU binfmt).
# This script never chroots into, executes, or mounts a guest tree on the host.
# No unpinned image tag, rolling apt repository, or unauthenticated package is
# allowed.  The result is a supplier archive for rv64_debian_rootfs_build.sh.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_DIR="${RV64_SD1_SOURCE_OUTPUT_DIR:-${ROOT_DIR}/build/rv64-debian-sd1-official-rootfs}"
ARCHIVE="${OUTPUT_DIR}/debian-trixie-riscv64-full.tar"
SOURCE_DIGEST="${RV64_SD1_DEBIAN_OCI_DIGEST:-}"
MAIN_SNAPSHOT="${RV64_SD1_DEBIAN_SNAPSHOT_UTC:-}"
SECURITY_SNAPSHOT="${RV64_SD1_DEBIAN_SECURITY_SNAPSHOT_UTC:-}"
MAIN_RELEASE_SHA="${RV64_SD1_DEBIAN_INRELEASE_SHA256:-}"
SECURITY_RELEASE_SHA="${RV64_SD1_DEBIAN_SECURITY_INRELEASE_SHA256:-}"
LOGIN_USER="${RV64_SD1_LOGIN_USER:-}"
LOGIN_HASH_FILE="${RV64_SD1_LOGIN_PASSWORD_HASH_FILE:-}"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
for tool in docker sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || fail "missing host command: $tool"
done
[[ "$SOURCE_DIGEST" =~ ^sha256:[[:xdigit:]]{64}$ ]] || \
  fail 'set RV64_SD1_DEBIAN_OCI_DIGEST=sha256:<64 hex> for official docker.io/library/debian (not an unpinned tag)'
[[ "$MAIN_SNAPSHOT" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || \
  fail 'set RV64_SD1_DEBIAN_SNAPSHOT_UTC=YYYYMMDDTHHMMSSZ (actual snapshot.debian.org timestamp)'
[[ "$SECURITY_SNAPSHOT" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || \
  fail 'set RV64_SD1_DEBIAN_SECURITY_SNAPSHOT_UTC=YYYYMMDDTHHMMSSZ (actual security snapshot timestamp)'
[[ "$MAIN_RELEASE_SHA" =~ ^[[:xdigit:]]{64}$ ]] || \
  fail 'set RV64_SD1_DEBIAN_INRELEASE_SHA256=<64 hex>; pin the official signed trixie InRelease bytes'
[[ "$SECURITY_RELEASE_SHA" =~ ^[[:xdigit:]]{64}$ ]] || \
  fail 'set RV64_SD1_DEBIAN_SECURITY_INRELEASE_SHA256=<64 hex>; pin the signed trixie-security InRelease bytes'
if [[ -n "$LOGIN_USER" || -n "$LOGIN_HASH_FILE" ]]; then
  [[ "$LOGIN_USER" =~ ^[a-z_][a-z0-9_-]{0,30}$ && "$LOGIN_USER" != root ]] || \
    fail 'RV64_SD1_LOGIN_USER must name a new ordinary Debian account (not root)'
  [[ -f "$LOGIN_HASH_FILE" && -r "$LOGIN_HASH_FILE" ]] || \
    fail 'RV64_SD1_LOGIN_PASSWORD_HASH_FILE must be a readable file containing a crypt hash, never plaintext'
  LOGIN_HASH="$(< "$LOGIN_HASH_FILE")"
  case "$LOGIN_HASH" in
    '$6$'*|'$y$'*) ;;
    *) fail 'login hash must be a SHA-512 crypt ($6$) or yescrypt ($y$) password hash' ;;
  esac
  [[ "$LOGIN_HASH" =~ ^[^[:space:]]{30,}$ ]] || \
    fail 'malformed login hash file; expected one long hash with no whitespace'
fi

IMAGE="docker.io/library/debian@${SOURCE_DIGEST}"
[[ ! -e "$ARCHIVE" && ! -e "${ARCHIVE}.sha256" && ! -e "${OUTPUT_DIR}/source.env" ]] || \
  fail "refusing to overwrite an existing supplier artifact under ${OUTPUT_DIR}"
mkdir -p "$OUTPUT_DIR"

printf 'Pinned OCI image: %s\nMain Debian snapshot: %s\nSecurity Debian snapshot: %s\n' \
  "$IMAGE" "$MAIN_SNAPSHOT" "$SECURITY_SNAPSHOT"
printf 'Guest programs may run in an isolated Docker linux/riscv64 container; this is NOT a host-native or RTL run.\n'

# A manifest-list digest is acceptable: Docker resolves its immutable
# linux/riscv64 child.  Never infer the architecture from the tag or host.
docker pull --platform linux/riscv64 "$IMAGE"
IMAGE_ARCH="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$IMAGE")"
[[ "$IMAGE_ARCH" == linux/riscv64 ]] || \
  fail "pinned source image resolves to ${IMAGE_ARCH}, not linux/riscv64"

# Docker isolation is deliberate: apt/dpkg must execute target-architecture
# maintainer scripts to CONFIGURE a real init system.  A tar of merely
# unpacked .debs is not a functional full distribution.  There is no bind
# mount, --privileged, host chroot, or host user binfmt installation here.
CONTAINER="$(docker create --platform linux/riscv64 --network bridge \
  --security-opt no-new-privileges "$IMAGE" sleep infinity)"
[[ -n "$CONTAINER" ]] || fail 'docker create returned no ID'
cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker start "$CONTAINER" >/dev/null || \
  fail 'cannot start linux/riscv64 Debian container; configure host QEMU/binfmt separately or use a native riscv64 Docker host'

# Override ALL original image apt sources.  Each valid .deb comes from two
# historical, signed Debian snapshots; neither "stable" nor "latest" is used.
docker exec -e SD1_MAIN="$MAIN_SNAPSHOT" -e SD1_SECURITY="$SECURITY_SNAPSHOT" \
  "$CONTAINER" /bin/sh -eu -c '
    test -r /usr/share/keyrings/debian-archive-keyring.gpg || {
      echo "missing official Debian archive keyring" >&2; exit 1;
    }
    rm -f /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources
    mkdir -p /etc/apt/sources.list.d /usr/sbin
    cat > /etc/apt/sources.list.d/aethercore-sd1-snapshot.sources <<EOF
Types: deb
URIs: https://snapshot.debian.org/archive/debian/${SD1_MAIN}/
Suites: trixie
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
Check-Valid-Until: no

Types: deb
URIs: https://snapshot.debian.org/archive/debian-security/${SD1_SECURITY}/
Suites: trixie-security
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
Check-Valid-Until: no
EOF
    # Required by Debian packaging: do not start host-inappropriate daemons
    # while building the guest tree.  systemd runs as guest PID1 only at boot.
    printf "#!/bin/sh\nexit 101\n" > /usr/sbin/policy-rc.d
    chmod 0755 /usr/sbin/policy-rc.d
  '

# The official minimal Debian OCI base may not ship a CA bundle yet. Apt
# must still use HTTPS (and must independently verify Debian GPG signatures
# and exact InRelease hashes). Supply the Actions runner's system trust
# anchors only for this isolated apt bootstrap, never disable TLS verification.
HOST_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
[[ -s "$HOST_CA_BUNDLE" ]] || fail 'runner has no usable trusted CA bundle for HTTPS apt bootstrap'
docker cp "$HOST_CA_BUNDLE" "$CONTAINER:/tmp/aethercore-sd1-bootstrap-ca.pem" || \
  fail 'cannot stage trusted HTTPS CA bundle in the isolated riscv64 container'
docker exec "$CONTAINER" /bin/sh -ec \
  'test -s /tmp/aethercore-sd1-bootstrap-ca.pem && chmod 0644 /tmp/aethercore-sd1-bootstrap-ca.pem'

docker exec -e DEBIAN_FRONTEND=noninteractive "$CONTAINER" \
  apt-get -o Acquire::Check-Valid-Until=false \
    -o Acquire::https::CaInfo=/tmp/aethercore-sd1-bootstrap-ca.pem \
    -o APT::Update::Error-Mode=any update \
  > "${OUTPUT_DIR}/apt-update.log" 2>&1 || \
  {
    sed -n '1,65p' "${OUTPUT_DIR}/apt-update.log" >&2
    fail "signed Debian snapshot apt update failed; inspect ${OUTPUT_DIR}/apt-update.log"
  }

# An InRelease is both GPG-signature-verified by apt and byte-pinned by the
# caller.  Timestamps that do not exist, expired/rotated signing keys, or a
# missing signed index fail closed before installing anything.
docker exec -e SD1_MAIN_SHA="$MAIN_RELEASE_SHA" \
  -e SD1_SECURITY_SHA="$SECURITY_RELEASE_SHA" "$CONTAINER" /bin/sh -eu -c '
    set -- /var/lib/apt/lists/*_dists_trixie_InRelease
    test "$#" -eq 1 && test -f "$1" || {
      echo "expected exactly one signed trixie InRelease" >&2; exit 1;
    }
    echo "${SD1_MAIN_SHA}  $1" | sha256sum -c -
    set -- /var/lib/apt/lists/*_dists_trixie-security_InRelease
    test "$#" -eq 1 && test -f "$1" || {
      echo "expected exactly one signed trixie-security InRelease" >&2; exit 1;
    }
    echo "${SD1_SECURITY_SHA}  $1" | sha256sum -c -
  ' > "${OUTPUT_DIR}/signed-release-check.log" 2>&1 || \
  {
    # The isolated Actions runner's local build/ directory is ephemeral and
    # is not uploaded on a failed source gate. Report ONLY public signed
    # index hash evidence now, so a mismatched snapshot does not cause an
    # opaque retry (and never print package credentials or private data).
    printf 'Pinned Debian signed index expected: trixie=%s trixie-security=%s\n' \
      "$MAIN_RELEASE_SHA" "$SECURITY_RELEASE_SHA" >&2
    printf 'APT update output (public sources only):\n' >&2
    sed -n '1,65p' "${OUTPUT_DIR}/apt-update.log" >&2
    printf 'APT index filenames:\n' >&2
    docker exec "$CONTAINER" /bin/sh -c \
      'find /var/lib/apt/lists -maxdepth 1 -type f -printf "%f\\n"' >&2 || true
    docker exec "$CONTAINER" /bin/sh -c '
      for index in /var/lib/apt/lists/*_dists_trixie_InRelease \
                   /var/lib/apt/lists/*_dists_trixie-security_InRelease; do
        test -f "$index" && sha256sum "$index"
      done
    ' >&2 || true
    sed -n '1,25p' "${OUTPUT_DIR}/signed-release-check.log" >&2
    fail "signed snapshot index changed or hash is wrong; inspect ${OUTPUT_DIR}/signed-release-check.log"
  }

# Explicit, bounded package request.  Apt may pull their REQUIRED
# dependencies from the same pinned snapshot; --no-install-recommends avoids
# accidental large desktop/firmware inclusion.  Do NOT install distro kernel,
# GRUB or OpenSBI: Product owns its kernel/FW payload independently.
docker exec -e DEBIAN_FRONTEND=noninteractive "$CONTAINER" \
  apt-get -o Acquire::https::CaInfo=/tmp/aethercore-sd1-bootstrap-ca.pem \
    install --yes --no-install-recommends \
    systemd systemd-sysv libpam-systemd udev dbus \
    login passwd util-linux mount procps kmod e2fsprogs \
    apt dpkg ca-certificates \
    bash coreutils dash findutils grep sed less \
    iproute2 iputils-ping netbase \
  > "${OUTPUT_DIR}/apt-install.log" 2>&1 || \
  fail "signed guest Debian package configuration failed; inspect ${OUTPUT_DIR}/apt-install.log"

# The archive must contain installed/configured packages, a real serial
# getty/login, a distro init, and an unmodified Debian package status DB.
docker exec "$CONTAINER" dpkg --audit \
  > "${OUTPUT_DIR}/dpkg-audit.txt" 2>&1 || fail 'dpkg reports unconfigured Debian packages'
[[ ! -s "${OUTPUT_DIR}/dpkg-audit.txt" ]] || fail 'dpkg reports partially configured Debian packages'
docker exec "$CONTAINER" /bin/sh -eu -c '
  test "$(dpkg --print-architecture)" = riscv64
  test -x /sbin/init
  test -x /usr/sbin/agetty || test -x /sbin/agetty
  test -x /usr/bin/login || test -x /bin/login
  test -x /bin/mount
  test -x /usr/bin/dpkg
  test -s /var/lib/dpkg/status
  test -f /usr/lib/systemd/system/serial-getty@.service ||
    test -f /lib/systemd/system/serial-getty@.service
  test ! -e /init
' || fail 'configured guest lacks an SD1 real-init/getty/login contract'

# The original official OCI image has a locked root account.  A getty prompt
# does NOT imply a usable login.  Optional explicit provisioning accepts ONLY
# a precomputed one-line crypt hash via stdin, not a plaintext password,
# command-line argument or environment variable forwarded to the container.
if [[ -n "$LOGIN_USER" ]]; then
  docker exec "$CONTAINER" useradd --create-home --shell /bin/bash "$LOGIN_USER" || \
    fail 'failed creating the explicitly requested serial-console account'
  printf '%s:%s\n' "$LOGIN_USER" "$LOGIN_HASH" | \
    docker exec -i "$CONTAINER" chpasswd --encrypted || \
    fail 'failed setting the explicitly supplied crypt password hash'
  printf 'console_login_user=%s\nconsole_login_credentials=caller-supplied-crypt-hash\n' \
    "$LOGIN_USER" > "${OUTPUT_DIR}/console-provisioning.txt"
else
  printf 'console_login_user=NONE\nconsole_login_credentials=NOT_PROVISIONED\n' \
    > "${OUTPUT_DIR}/console-provisioning.txt"
  printf 'WARNING: no guest user/password provisioned; getty can prompt, but default locked root cannot authenticate.\n' >&2
fi

docker exec "$CONTAINER" dpkg-query -W -f='${binary:Package}\t${Version}\n' \
  > "${OUTPUT_DIR}/installed-packages.tsv"
docker exec "$CONTAINER" apt-get clean
docker exec "$CONTAINER" rm -f /tmp/aethercore-sd1-bootstrap-ca.pem
# Prevent an image-build-only service-start policy from disabling ordinary
# Debian package maintenance on the eventual physical FPGA system.
docker exec "$CONTAINER" rm -f /usr/sbin/policy-rc.d

# Export the configured container filesystem, preserving native numeric owners
# and guest symlinks.  The existing SD1 builder will SHA-check and safely
# rebase/check guest-absolute symlinks before privileged host extraction.
docker export --output "$ARCHIVE" "$CONTAINER" || fail 'docker export failed'
[[ -s "$ARCHIVE" ]] || fail 'Docker exported an empty rootfs archive'
sha256sum "$ARCHIVE" > "${ARCHIVE}.sha256"
ARCHIVE_SHA="$(sha256sum "$ARCHIVE")"
ARCHIVE_SHA="${ARCHIVE_SHA%% *}"

{
  printf 'suite=trixie\narch=riscv64\noci=%s\n' "$IMAGE"
  printf 'main_snapshot=%s\nmain_inrelease_sha256=%s\n' "$MAIN_SNAPSHOT" "$MAIN_RELEASE_SHA"
  printf 'security_snapshot=%s\nsecurity_inrelease_sha256=%s\n' "$SECURITY_SNAPSHOT" "$SECURITY_RELEASE_SHA"
  printf 'installed_packages_sha256=%s\n' "$(sha256sum "${OUTPUT_DIR}/installed-packages.tsv" | cut -d' ' -f1)"
  printf 'archive_sha256=%s\n' "$ARCHIVE_SHA"
} > "${OUTPUT_DIR}/source-provenance.txt"
{
  printf 'export RV64_DEBIAN_STAGE=sd1\n'
  printf 'export RV64_DEBIAN_FULL_ROOTFS_ARCHIVE=%q\n' "$ARCHIVE"
  printf 'export RV64_DEBIAN_FULL_ROOTFS_SHA256=%q\n' "$ARCHIVE_SHA"
} > "${OUTPUT_DIR}/source.env"

printf 'Created genuine Debian riscv64 rootfs archive: %s\nSHA256: %s\n' "$ARCHIVE" "$ARCHIVE_SHA"
printf 'To supply existing SD1 builder later: source %q\n' "${OUTPUT_DIR}/source.env"
printf 'This is an OFFLINE SUPPLY artifact, not proof of Linux/RTL/FPGA boot.\n'
