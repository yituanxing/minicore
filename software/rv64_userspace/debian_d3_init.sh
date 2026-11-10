#!/bin/sh
# D3 is entered only with an independently prepared, complete Debian rootfs
# containing genuine /sbin/init, agetty and login. This script does not
# emulate a distribution init: it hands PID 1 to Debian's native init.

exec </dev/console >/dev/console 2>&1
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
printf 'AETHERCORE_DEBIAN_D3_NATIVE_INIT_HANDOFF\n'

for dir in /proc /sys /dev /run; do
  [ -d "$dir" ] || /bin/mkdir -p "$dir" || exit 31
done
/bin/mount -t proc proc /proc || exit 32
/bin/mount -t sysfs sysfs /sys || exit 33
/bin/mount -t devtmpfs devtmpfs /dev || exit 34
/bin/mount -t tmpfs -o mode=0755 tmpfs /run || exit 35

[ -x /sbin/init ] || exit 36
exec /sbin/init
