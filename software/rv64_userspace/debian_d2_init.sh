#!/bin/sh
# D2 exercises real Debian LP64D programs, fork/exec/wait, signals and a
# writable filesystem. It is an application milestone, not a replacement for
# Debian's native init/services (D3) or a persistent block rootfs (D4).

exec </dev/console >/dev/console 2>&1
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
printf 'AETHERCORE_DEBIAN_D2_START\n'

fail() {
  printf 'AETHERCORE_DEBIAN_D2_FAIL:%s\n' "$1"
  exit 1
}

[ "$(/usr/bin/dpkg --print-architecture)" = riscv64 ] || fail arch
[ -r /etc/debian_version ] || fail version

# A real shell, mount, coreutils, and GNU/Linux processes must work under
# the DUT. The root filesystem used by this milestone is an initramfs; /run
# is writable but deliberately not presented as durable storage.
/bin/mount -t proc proc /proc || fail proc
/bin/mount -t sysfs sysfs /sys || fail sysfs
/bin/mount -t tmpfs -o mode=0755 tmpfs /run || fail tmpfs
/bin/mkdir -p /run/aethercore-d2 || fail mkdir
printf 'rv64gc-lp64d\n' > /run/aethercore-d2/identity || fail write
[ "$(/bin/cat /run/aethercore-d2/identity)" = rv64gc-lp64d ] || fail read

# Spawn independent native Debian processes and check wait/exit propagation.
(/bin/echo child-a > /run/aethercore-d2/a) &
child_a=$!
(/bin/echo child-b > /run/aethercore-d2/b) &
child_b=$!
wait "$child_a" || fail wait-a
wait "$child_b" || fail wait-b
[ "$(/bin/cat /run/aethercore-d2/a)" = child-a ] || fail child-a
[ "$(/bin/cat /run/aethercore-d2/b)" = child-b ] || fail child-b

# Shell signal/trap semantics exercise user/kernel signal delivery without
# fabricating a success marker merely because the process started.
signal_seen=0
trap 'signal_seen=1' USR1
kill -USR1 "$$" || fail signal-send
[ "$signal_seen" -eq 1 ] || fail signal-delivery
trap - USR1

/bin/uname -m || fail uname
/bin/cat /etc/debian_version || fail release
/usr/local/libexec/aethercore-d2-native-probe || fail native-fp-threads-futex-vm
printf 'DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK\n'

# Preserve the console for inspection after the D2 milestone. Serial login
# and the distribution's own /sbin/init are deliberately reserved for D3.
while :; do
  /bin/sleep 1 || fail sleep
done
