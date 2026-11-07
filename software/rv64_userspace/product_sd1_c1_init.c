// Tiny, rootfs-agnostic PID1 for a future Product SD1 C1 Linux checkpoint.
// This lives in a dedicated built-in initramfs, never in Debian/Alpine.
// It gates AFTER generic Linux init but BEFORE mounting the full ext4 root.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <termios.h>
#include <unistd.h>

static int console_fd = -1;
static char root_device[32] = "/dev/mmcblk0p1";

static void say(const char *message) {
  const size_t len = strlen(message);
  if (console_fd >= 0) (void)write(console_fd, message, len);
}

__attribute__((noreturn)) static void die(const char *stage) {
  char line[192];
  int saved_errno = errno;
  int len = snprintf(line, sizeof(line),
                     "AETHERCORE_C1_FAIL stage=%s errno=%d\n", stage, saved_errno);
  if (len > 0 && (size_t)len < sizeof(line)) say(line);
  for (;;) pause();  // PID1 must not exit and trigger an unrelated kernel panic.
}

static void ensure_dir(const char *path) {
  if (mkdir(path, 0755) != 0 && errno != EEXIST) die("mkdir");
}

static void mount_or_existing(const char *source, const char *target,
                              const char *fs) {
  if (mount(source, target, fs, 0, NULL) != 0 && errno != EBUSY)
    die("common_mount");
}

static void select_root_from_cmdline(void) {
  char cmdline[4096];
  int fd = open("/proc/cmdline", O_RDONLY | O_CLOEXEC);
  if (fd < 0) die("cmdline_open");
  ssize_t size = read(fd, cmdline, sizeof(cmdline) - 1);
  int saved_errno = errno;
  close(fd);
  if (size <= 0) {
    errno = size == 0 ? EINVAL : saved_errno;
    die("cmdline_read");
  }
  cmdline[size] = '\0';
  bool found = false;
  for (char *cursor = cmdline; *cursor;) {
    while (*cursor == ' ') ++cursor;
    if (!*cursor) break;
    char *start = cursor;
    while (*cursor && *cursor != ' ') ++cursor;
    const size_t length = (size_t)(cursor - start);
    if (length < 5 || memcmp(start, "root=", 5) != 0) continue;
    // The Product physical SPI-SD and QEMU-virt block oracle must use the
    // same distro-independent /init, but different *built-in* block drivers.
    // Do not accept paths under an arbitrary guest-controlled mount target.
    const char *value = start + 5;
    const size_t value_length = length - 5;
    if (found ||
        !((value_length == sizeof("/dev/mmcblk0p1") - 1 &&
           memcmp(value, "/dev/mmcblk0p1", value_length) == 0) ||
          (value_length == sizeof("/dev/vda1") - 1 &&
           memcmp(value, "/dev/vda1", value_length) == 0))) {
      errno = EINVAL;
      die("cmdline_root_invalid");
    }
    memcpy(root_device, value, value_length);
    root_device[value_length] = '\0';
    found = true;
  }
  if (!found) { errno = EINVAL; die("cmdline_root_missing"); }
}

static void wait_for_block_root(void) {
  struct stat st;
  // Linux may already have read MBR sectors while enumerating the block
  // device. The host's C1 consumed-sector ledger enforces byte equivalence
  // for ALL such reads before a replacement media image may be attached.
  for (unsigned attempt = 0; attempt < 60; ++attempt) {
    if (stat(root_device, &st) == 0 && S_ISBLK(st.st_mode)) return;
    say("AETHERCORE_C1_WAIT_SD\n");
    sleep(1);
  }
  errno = ENODEV;
  die("sd_block_device_timeout");
}

static void wait_for_release(void) {
  static const char expected[] = "AETHERCORE_C1_GO\n";
  // /dev/console is reliable for output, but not necessarily for receive.
  // Read the platform's real 16550 TTY, with input enabled and canonical
  // buffering disabled for this exact host-to-guest checkpoint token.
  int input_fd = open("/dev/ttyS0", O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK);
  if (input_fd < 0) die("uart_input_open");
  int flags = fcntl(input_fd, F_GETFL);
  if (flags < 0) die("uart_input_flags");
  struct termios saved;
  if (tcgetattr(input_fd, &saved) != 0) die("uart_termios_get");
  struct termios raw = saved;
  raw.c_cflag |= CLOCAL | CREAD;
  raw.c_iflag &= ~(ICRNL | INLCR | IGNCR | IXON | IXOFF);
  raw.c_lflag &= ~(ICANON | ECHO);
  raw.c_cc[VMIN] = 1;
  raw.c_cc[VTIME] = 0;
  if (tcsetattr(input_fd, TCSANOW, &raw) != 0) die("uart_termios_set");
  if (fcntl(input_fd, F_SETFL, flags & ~O_NONBLOCK) != 0)
    die("uart_input_blocking");
  char command[sizeof(expected)] = {0};
  size_t position = 0;
  while (position < sizeof(expected) - 1) {
    char byte;
    const ssize_t received = read(input_fd, &byte, 1);
    if (received < 0 && errno == EINTR) continue;
    if (received != 1) die("uart_release_read");
    command[position++] = byte;
    if (byte == '\n') break;
  }
  if (position != sizeof(expected) - 1 ||
      memcmp(command, expected, sizeof(expected) - 1) != 0) {
    errno = EINVAL;
    die("uart_release_token");
  }
  if (tcsetattr(input_fd, TCSANOW, &saved) != 0)
    die("uart_termios_restore");
  close(input_fd);
  say("AETHERCORE_C1_GO_RECEIVED\n");
}

int main(void) {
  if (getpid() != 1) { errno = EPERM; die("not_pid1"); }
  ensure_dir("/dev");
  mount_or_existing("devtmpfs", "/dev", "devtmpfs");
  console_fd = open("/dev/console", O_RDWR | O_NOCTTY);
  if (console_fd < 0) die("console_open");
  if (dup2(console_fd, STDIN_FILENO) < 0 ||
      dup2(console_fd, STDOUT_FILENO) < 0 ||
      dup2(console_fd, STDERR_FILENO) < 0) die("console_stdio");
  ensure_dir("/proc"); ensure_dir("/sys"); ensure_dir("/newroot");
  mount_or_existing("proc", "/proc", "proc");
  mount_or_existing("sysfs", "/sys", "sysfs");
  select_root_from_cmdline();
  wait_for_block_root();

  // A full-clock-boundary RTL/DDR/SD checkpoint is taken while PID1 blocks
  // here. No external Debian or Alpine filesystem has been mounted yet.
  say("AETHERCORE_C1_READY rootfs_unmounted=1\n");
  wait_for_release();
  say("AETHERCORE_C1_ROOTFS_MOUNT_BEGIN\n");
  if (mount(root_device, "/newroot", "ext4", 0, NULL) != 0)
    die("ext4_mount");
  say("AETHERCORE_C1_ROOTFS_MOUNT_OK\n");

  // Move kernel pseudo-filesystems into the selected real root before
  // launching the unmodified distribution /sbin/init as PID1.
  if (mount("/dev", "/newroot/dev", NULL, MS_MOVE, NULL) != 0)
    die("move_dev");
  if (mount("/proc", "/newroot/proc", NULL, MS_MOVE, NULL) != 0)
    die("move_proc");
  if (mount("/sys", "/newroot/sys", NULL, MS_MOVE, NULL) != 0)
    die("move_sys");
  if (chdir("/newroot") != 0 || mount(".", "/", NULL, MS_MOVE, NULL) != 0 ||
      chroot(".") != 0 || chdir("/") != 0)
    die("switch_root");

  say("AETHERCORE_C1_NATIVE_INIT_EXEC\n");
  char *const argv[] = {"/sbin/init", NULL};
  char *const envp[] = {"HOME=/", "PATH=/usr/sbin:/usr/bin:/sbin:/bin",
                        "TERM=linux", NULL};
  execve("/sbin/init", argv, envp);
  die("native_init_exec");
}
