# G5-SD1: pinned official Debian riscv64 rootfs supplier

## Scope and why it exists

`tools/ci/rv64_debian_rootfs_build.sh` intentionally **does not** build a
full distribution in the SD1 stage.  It rejects a missing or unpinned
supplier archive.  D1's official Debian *slim* OCI tree contains working
Debian userspace utilities, but it **does not** establish a real service
manager, serial getty/login session, or a configured full Debian root.  A
simple `docker export` of D1's slim image will be rejected by SD1.

`tools/ci/rv64_debian_sd1_official_full_rootfs_prepare.sh` closes this
artifact-supply gap **off the FPGA**, using:

1. Official Debian 13 **trixie** `linux/riscv64` OCI image, **pinned by the
   exact OCI SHA256 digest**.  A mutable tag alone is refused.
2. Debian's official `snapshot.debian.org` repositories for `trixie` and
   `trixie-security`, each with an explicit historical UTC timestamp.
3. The signed Debian archive keyring contained in that pinned official
   image.  APT must validate each repository's `InRelease` signature, and
   the two exact signed index files must additionally match independently
   supplied SHA256 hashes.  Invalid signatures, stale keyrings, unavailable
   timestamps and mismatched bytes **fail closed**.
4. A deliberately bounded Debian package request: `systemd`, `systemd-sysv`,
   `libpam-systemd`, `udev`, `dbus`, `login`, `passwd`, `util-linux`, `mount`,
   `procps`, `kmod`, `e2fsprogs`, `apt`, `dpkg`, `ca-certificates`, shells,
   core utilities and basic IP tools.  Only required dependencies are added;
   `--no-install-recommends` is set.  No custom init/login binaries or
   distro-provided kernel, firmware, bootloader or desktop are injected.
5. Native package configuration by `apt-get`/`dpkg` **inside an isolated
   `linux/riscv64` Docker container**, then a full numeric-owner-preserving
   `docker export` tar.  The script does not execute any guest ELF directly
   on the host, chroot into the guest, mount the guest filesystem, or change
   the host's binfmt registrations.

**This is an artifact construction tool, not a Linux boot/test script.** Do
not run it, the rootfs builder, the SD-image builder, or any RTL simulator in
a source-only architecture round.

Debian supports `riscv64` as an official release architecture from trixie.
The archive, Docker and snapshot interfaces documented here are official:

- [Debian 13/trixie release and riscv64](https://www.debian.org/releases/trixie/)
- [Debian's historical archive snapshots](https://snapshot.debian.org/)
- [Docker digest- and platform-aware pull](https://docs.docker.com/reference/cli/docker/image/pull/)
- [Docker full container filesystem export](https://docs.docker.com/reference/cli/docker/container/export/)

## Source lock must be supplied before construction

The inputs below are intentionally **unset** in source control.  Never invent
a digest, silently substitute `latest`, or use a rolling `stable` source just
to get a build to proceed.  First select an official Debian OCI image digest
containing a riscv64 image, and actual Debian snapshot timestamps whose
signed `InRelease` files exist.  Obtain and check their hashes independently;
for example, download each official `.../dists/<suite>/InRelease` over HTTPS,
verify its detached or inline GPG signature against the official Debian
archive keyring, and record its SHA256.  The supplier repeats signature and
SHA checks **inside the pinned Debian container** before installing packages.

```bash
# EXAMPLE FORMAT ONLY: all these values must be replaced with verified real
# values selected for this particular artifact.  No values are preapproved.
export RV64_SD1_DEBIAN_OCI_DIGEST=sha256:<verified-64-hex>
export RV64_SD1_DEBIAN_SNAPSHOT_UTC=YYYYMMDDTHHMMSSZ
export RV64_SD1_DEBIAN_SECURITY_SNAPSHOT_UTC=YYYYMMDDTHHMMSSZ
export RV64_SD1_DEBIAN_INRELEASE_SHA256=<verified-64-hex>
export RV64_SD1_DEBIAN_SECURITY_INRELEASE_SHA256=<verified-64-hex>

# Optional (recommended before the eventual FPGA login acceptance): choose
# an unprivileged guest user and a locally generated PASSWORD HASH.  This
# exact format uses a password prompt; it never writes a plaintext password.
#
# umask 077
# openssl passwd -6 > /secure/location/sd1-login.sha512
# export RV64_SD1_LOGIN_USER=aether
# export RV64_SD1_LOGIN_PASSWORD_HASH_FILE=/secure/location/sd1-login.sha512

# To construct the supplier archive on a separately authorized host later:
bash tools/ci/rv64_debian_sd1_official_full_rootfs_prepare.sh

# After successful construction, exact source identity and export values are:
source build/rv64-debian-sd1-official-rootfs/source.env
# These three exports are then ready for the EXISTING SD1 rootfs builder:
# RV64_DEBIAN_STAGE=sd1
# RV64_DEBIAN_FULL_ROOTFS_ARCHIVE=<absolute ...full.tar>
# RV64_DEBIAN_FULL_ROOTFS_SHA256=<actual-64-hex>
```

The helper refuses to overwrite an existing supplier archive and leaves
`source-provenance.txt`, `installed-packages.tsv`, apt transaction logs, the
signed index hash checks, `dpkg-audit.txt`, `console-provisioning.txt`, the
full rootfs tar and the
tar `.sha256`.  Preserve these together as the source lock.  The tar digest
is **the exact identity consumed by the existing builder**, not the OCI
digest.  Docker export metadata/order are not promised byte-for-byte
identical on a second host; any independently rebuilt tar must be hashed
and reviewed instead of claiming the old artifact's digest.  The signed apt
snapshot and pinned OCI content, plus `installed-packages.tsv`, make source
package provenance auditable; they do not prove FPGA boot success.

The rootfs builder will still perform its own SHA verification, safety
checking of tar member paths and symlink targets, native riscv64 LP64D ELF
verification, and real serial-getty service wiring.  The downstream SD-image
builder subsequently stages the Debian archive on a writable ext4 SD card;
no distro `/init` wrapper is provided.  The Product OpenSBI/Linux Image,
device tree, SD-controller integration and bootargs remain separately pinned
by the existing SD1 contract.

## Isolation, host requirements and limits

An amd64 host generally requires QEMU user-mode emulation already registered
for `riscv64` (`binfmt_misc`) for Docker to execute riscv64 package
configuration scripts.  The helper **does not install or register this host
facility**.  It fails if the explicitly platform-selected container cannot
start.  Running the supplier helper is a separate, opt-in and potentially
time-consuming dependency-download operation; it does not run a guest kernel,
QEMU system machine, Verilator, gsim, FPGA synthesis or full Linux validation.
Docker default namespaces isolate the userspace construction; it does not
request `--privileged`, host mounts or access to host device nodes.  A native
riscv64 Docker host may perform the same operation without emulation.

Systemd services are prevented from starting *during image construction*
through a temporary `policy-rc.d`, removed from the exported rootfs; the
final guest must use the real systemd PID1 when mounted as `/`.  No password
is hardcoded, and the supplier does not enable anonymous/root autologin.
The original official OCI image normally has **locked root**.  Unless the
optional `RV64_SD1_LOGIN_USER` and `RV64_SD1_LOGIN_PASSWORD_HASH_FILE`
are both supplied, the helper emits a loud warning and writes
`console_login_credentials=NOT_PROVISIONED` into
`console-provisioning.txt`.  The future expensive board-level login run
**must not begin** without resolving that condition.  The password is
entered interactively when generating a local SHA-512 crypt (`$6$`) hash;
only the hash is read by the supplier, transferred to `chpasswd --encrypted`
via container stdin and stored in Debian's `/etc/shadow`.  The hash is
security-sensitive because it permits offline guessing: protect the
supplier tar and provisioning file; discard the input hash file using your
normal credential-management policy when appropriate.  A getty prompt
alone does not prove successful login/authentication.

The apt-list checks use APT's conventional basename suffixes
`_dists_trixie_InRelease` and `_dists_trixie-security_InRelease`; the script
requires exactly one file matching each suffix.  Should a future APT
version change those names, this supplier will **stop before package
installation** rather than falsely claiming an authenticated rootfs.  The
actual paths are not guaranteed or tested by this source-only change.

No first-run promise can be made about mmc SPI device discovery, boot timings,
DDR PHY, missing kernel drivers, interrupt wiring, memory pressure, serial
login or SD card electrical behavior from a rootfs tar.  These remain
independent board/kernel integration gates, to be checked by source audit and
short bounded checks before **one** consciously scheduled long run.

## Why not simply extract .deb or run mmdebstrap on the host?

A debootstrap/mmdebstrap unpack-only tree may contain native ELF binaries
but leaves service packages **unconfigured**.  Debian init, login/PAM,
systemd unit wiring, package triggers and dpkg metadata require target
maintainer-script configuration.  Running ordinary cross-architecture
`debootstrap --second-stage` on the build host without a controlled emulator
violates the no-guest-execution boundary; running it without target execution
does not yield a qualified full Debian.  An explicitly isolated Docker
container with a pinned native Debian image and signed, pinned apt snapshot
performs the required configuration without touching the host root or guest
boot path.  The file archive is still inspected by the downstream builder.
