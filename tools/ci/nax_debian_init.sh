#!/bin/sh
# Experimental native Debian riscv64 userland bootstrap for NaxRiscv RTL.
# This is a Debian initramfs baseline, not a full systemd distribution boot.
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH

mount -t proc proc /proc 2>/dev/null || true
mount -t sysfs sysfs /sys 2>/dev/null || true
mount -t devtmpfs devtmpfs /dev 2>/dev/null || true

echo NAX_DEBIAN_INIT_READY
if [ -r /etc/debian_version ]; then
    echo NAX_DEBIAN_RELEASE="$(cat /etc/debian_version)"
else
    echo NAX_DEBIAN_RELEASE_MISSING
fi
echo NAX_DEBIAN_ARCH="$(dpkg --print-architecture)"
echo NAX_DEBIAN_PID1="$$"

exec /bin/sh -i </dev/console >/dev/console 2>&1
