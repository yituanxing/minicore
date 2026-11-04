#!/bin/sh
# First REAL Debian riscv64/LP64D userspace proof. This script is interpreted
# by Debian's dynamically linked /bin/dash, not by the static G4 assembly PID1.
# A later milestone boots Debian's unmodified /sbin/init and services.

exec </dev/console >/dev/console 2>&1
printf 'AETHERCORE_DEBIAN_DYNAMIC_INIT_START\n'

if [ "$(/usr/bin/dpkg --print-architecture)" != riscv64 ]; then
  printf 'AETHERCORE_DEBIAN_ARCH_MISMATCH\n'
  exit 11
fi
if [ ! -r /etc/debian_version ]; then
  printf 'AETHERCORE_DEBIAN_VERSION_MISSING\n'
  exit 12
fi

/bin/cat /etc/debian_version || exit 13
/bin/uname -m || exit 14
printf 'DEBIAN_RISCV64_GLIBC_PID1_OK\n'

# Keep PID1 alive for UART observation. This executes genuine Debian shell,
# coreutils, dpkg and glibc with the kernel's normal fork/exec/wait paths.
while :; do
  /bin/sleep 1
done
