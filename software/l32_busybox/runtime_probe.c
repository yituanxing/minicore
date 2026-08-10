#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define L32_FUTEX_WAIT 0
#define L32_FUTEX_WAKE 1

static int fail(const char *test, const char *what) {
    fprintf(stderr, "L32_PROBE_FAIL test=%s step=%s errno=%d\n", test, what, errno);
    return 1;
}

static int write_all(int fd, const void *buf, size_t len) {
    const unsigned char *p = (const unsigned char *)buf;
    while (len != 0) {
        ssize_t n = write(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        p += (size_t)n;
        len -= (size_t)n;
    }
    return 0;
}

static int read_exact(int fd, void *buf, size_t len) {
    unsigned char *p = (unsigned char *)buf;
    while (len != 0) {
        ssize_t n = read(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        p += (size_t)n;
        len -= (size_t)n;
    }
    return 0;
}

static int wait_child_ok(const char *test, pid_t pid) {
    int status = 0;
    if (waitpid(pid, &status, 0) != pid) return fail(test, "waitpid");
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        fprintf(stderr, "L32_PROBE_FAIL test=%s step=child-status status=%d\n", test, status);
        return 1;
    }
    return 0;
}

static int test_vfs(void) {
    static const char path_a[] = "/tmp/l32-probe-a";
    static const char path_b[] = "/tmp/l32-probe-b";
    static const char first[] = "alpha\nbeta\n";
    static const char extra[] = "gamma\n";
    static const char expected[] = "alpha\nbeta\ngamma\n";
    char buf[sizeof(expected)] = {0};
    struct stat st;

    (void)unlink(path_a);
    (void)unlink(path_b);

    int fd = open(path_a, O_CREAT | O_TRUNC | O_RDWR, 0600);
    if (fd < 0) return fail("vfs", "open-create");
    if (write_all(fd, first, sizeof(first) - 1) != 0) return fail("vfs", "write-first");
    if (lseek(fd, 0, SEEK_SET) != 0) return fail("vfs", "lseek-zero");
    memset(buf, 0, sizeof(buf));
    if (read_exact(fd, buf, sizeof(first) - 1) != 0) return fail("vfs", "read-first");
    if (memcmp(buf, first, sizeof(first) - 1) != 0) return fail("vfs", "compare-first");
    if (fstat(fd, &st) != 0) return fail("vfs", "fstat");
    if (!S_ISREG(st.st_mode) || st.st_size != (off_t)(sizeof(first) - 1)) {
        errno = 0;
        return fail("vfs", "fstat-values");
    }
    if (close(fd) != 0) return fail("vfs", "close-first");

    if (rename(path_a, path_b) != 0) return fail("vfs", "rename");
    errno = 0;
    if (access(path_a, F_OK) == 0 || errno != ENOENT) return fail("vfs", "old-name-gone");

    fd = open(path_b, O_WRONLY | O_APPEND);
    if (fd < 0) return fail("vfs", "open-append");
    if (write_all(fd, extra, sizeof(extra) - 1) != 0) return fail("vfs", "write-append");
    if (close(fd) != 0) return fail("vfs", "close-append");

    if (stat(path_b, &st) != 0) return fail("vfs", "stat-renamed");
    if (!S_ISREG(st.st_mode) || st.st_size != (off_t)(sizeof(expected) - 1)) {
        errno = 0;
        return fail("vfs", "stat-values");
    }

    fd = open(path_b, O_RDONLY);
    if (fd < 0) return fail("vfs", "open-readback");
    memset(buf, 0, sizeof(buf));
    if (read_exact(fd, buf, sizeof(expected) - 1) != 0) return fail("vfs", "readback");
    if (memcmp(buf, expected, sizeof(expected) - 1) != 0) return fail("vfs", "compare-readback");
    if (close(fd) != 0) return fail("vfs", "close-readback");

    if (unlink(path_b) != 0) return fail("vfs", "unlink");
    errno = 0;
    fd = open(path_b, O_RDONLY);
    if (fd >= 0 || errno != ENOENT) {
        if (fd >= 0) close(fd);
        return fail("vfs", "enoent");
    }

    puts("L32_PROBE_VFS_PASS");
    return 0;
}

static int test_fd(void) {
    static const char path[] = "/tmp/l32-probe-fd";
    static const char payload[] = "fd-table-shared-offset";
    char buf[sizeof(payload)] = {0};
    (void)unlink(path);

    int fd = open(path, O_CREAT | O_TRUNC | O_RDWR, 0600);
    if (fd < 0) return fail("fd", "open");
    int dupfd = dup(fd);
    if (dupfd < 0) return fail("fd", "dup");
    if (write_all(fd, payload, sizeof(payload) - 1) != 0) return fail("fd", "write");
    if (lseek(dupfd, 0, SEEK_SET) != 0) return fail("fd", "shared-lseek");
    if (read_exact(fd, buf, sizeof(payload) - 1) != 0) return fail("fd", "shared-read");
    if (memcmp(buf, payload, sizeof(payload) - 1) != 0) return fail("fd", "compare");

    const int target = 57;
    if (dup2(dupfd, target) != target) return fail("fd", "dup2");
    if (fcntl(target, F_SETFD, FD_CLOEXEC) != 0) return fail("fd", "set-cloexec");
    if ((fcntl(target, F_GETFD) & FD_CLOEXEC) == 0) return fail("fd", "get-cloexec");
    if (close(fd) != 0 || close(dupfd) != 0 || close(target) != 0) return fail("fd", "close");
    if (unlink(path) != 0) return fail("fd", "unlink");

    puts("L32_PROBE_FD_PASS");
    return 0;
}

static int test_dir(void) {
    static const char dirpath[] = "/tmp/l32-probe-dir";
    static const char data[] = "directory-data";
    struct stat st;
    int found = 0;

    (void)unlink("/tmp/l32-probe-dir/a");
    (void)unlink("/tmp/l32-probe-dir/b");
    (void)rmdir(dirpath);
    if (mkdir(dirpath, 0700) != 0) return fail("dir", "mkdir");
    int dfd = open(dirpath, O_RDONLY | O_DIRECTORY);
    if (dfd < 0) return fail("dir", "open-dir");
    int fd = openat(dfd, "a", O_CREAT | O_TRUNC | O_RDWR, 0600);
    if (fd < 0) return fail("dir", "openat");
    if (write_all(fd, data, sizeof(data) - 1) != 0) return fail("dir", "write");
    if (close(fd) != 0) return fail("dir", "close-file");
    if (fstatat(dfd, "a", &st, 0) != 0 || !S_ISREG(st.st_mode) || st.st_size != (off_t)(sizeof(data) - 1)) {
        return fail("dir", "fstatat");
    }
    if (renameat(dfd, "a", dfd, "b") != 0) return fail("dir", "renameat");

    DIR *dir = opendir(dirpath);
    if (dir == NULL) return fail("dir", "opendir");
    for (;;) {
        errno = 0;
        struct dirent *de = readdir(dir);
        if (de == NULL) {
            if (errno != 0) return fail("dir", "readdir");
            break;
        }
        if (strcmp(de->d_name, "b") == 0) found = 1;
    }
    if (closedir(dir) != 0) return fail("dir", "closedir");
    if (!found) return fail("dir", "entry-missing");
    if (unlinkat(dfd, "b", 0) != 0) return fail("dir", "unlinkat");
    if (close(dfd) != 0) return fail("dir", "close-dir");
    if (rmdir(dirpath) != 0) return fail("dir", "rmdir");

    puts("L32_PROBE_DIR_PASS");
    return 0;
}

static uint32_t checksum32(const unsigned char *p, size_t len) {
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < len; ++i) h = (h ^ p[i]) * 16777619u;
    return h;
}

static int test_vm(void) {
    const size_t page = 4096;
    const size_t len = page * 4;
    unsigned char *p = mmap(NULL, len, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) return fail("vm", "mmap");

    for (size_t i = 0; i < len; ++i) {
        if (p[i] != 0) return fail("vm", "zero-fill");
        p[i] = (unsigned char)((i * 37u + 11u) & 0xffu);
    }
    const uint32_t before = checksum32(p, len);
    if (before != 0x57df1dc5u) {
        fprintf(stderr, "L32_PROBE_FAIL test=vm step=checksum-before got=%08x\n", before);
        return 1;
    }

    if (mprotect(p + page, page, PROT_READ) != 0) return fail("vm", "mprotect-ro");
    if (mprotect(p + page, page, PROT_READ | PROT_WRITE) != 0) return fail("vm", "mprotect-rw");
    p[page] ^= 0x5au;
    if (p[page] != (unsigned char)((((page * 37u + 11u) & 0xffu)) ^ 0x5au)) {
        return fail("vm", "write-after-mprotect");
    }
    if (munmap(p, len) != 0) return fail("vm", "munmap");

    puts("L32_PROBE_VM_PASS");
    return 0;
}

static int test_cow(void) {
    const size_t page = 4096;
    volatile uint32_t *p = mmap(NULL, page, PROT_READ | PROT_WRITE,
                                MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) return fail("cow", "mmap");
    p[0] = 0x11223344u;
    p[1] = 0x55667788u;

    pid_t pid = fork();
    if (pid < 0) return fail("cow", "fork");
    if (pid == 0) {
        if (p[0] != 0x11223344u || p[1] != 0x55667788u) _exit(41);
        p[0] = 0xa5a5a5a5u;
        p[1] = 0x5a5a5a5au;
        if (p[0] != 0xa5a5a5a5u || p[1] != 0x5a5a5a5au) _exit(42);
        _exit(0);
    }

    if (wait_child_ok("cow", pid) != 0) return 1;
    if (p[0] != 0x11223344u || p[1] != 0x55667788u) return fail("cow", "parent-cow");
    if (munmap((void *)p, page) != 0) return fail("cow", "munmap");

    puts("L32_PROBE_COW_PASS");
    return 0;
}

static volatile sig_atomic_t signal_seen;

static void on_usr1(int signo) {
    if (signo == SIGUSR1) signal_seen = 0x35;
}

static int test_signal(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_usr1;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGUSR1, &sa, NULL) != 0) return fail("signal", "sigaction");
    if (kill(getpid(), SIGUSR1) != 0) return fail("signal", "kill-self");
    if (signal_seen != 0x35) return fail("signal", "handler-return");
    puts("L32_PROBE_SIGNAL_PASS");
    return 0;
}

static int test_time(void) {
    struct timespec a, b, req = { .tv_sec = 0, .tv_nsec = 1000000 };
    if (clock_gettime(CLOCK_MONOTONIC, &a) != 0) return fail("time", "clock-before");
    while (nanosleep(&req, &req) != 0) {
        if (errno != EINTR) return fail("time", "nanosleep");
    }
    if (clock_gettime(CLOCK_MONOTONIC, &b) != 0) return fail("time", "clock-after");
    if (b.tv_sec < a.tv_sec || (b.tv_sec == a.tv_sec && b.tv_nsec <= a.tv_nsec)) {
        errno = 0;
        return fail("time", "monotonic-progress");
    }
    puts("L32_PROBE_TIME_PASS");
    return 0;
}

static int test_unix_socket(void) {
    int sv[2];
    static const char ping[] = "socket-ping";
    static const char pong[] = "socket-pong";
    char buf[sizeof(ping) > sizeof(pong) ? sizeof(ping) : sizeof(pong)];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) return fail("unix", "socketpair");
    pid_t pid = fork();
    if (pid < 0) return fail("unix", "fork");
    if (pid == 0) {
        close(sv[0]);
        memset(buf, 0, sizeof(buf));
        if (read_exact(sv[1], buf, sizeof(ping) - 1) != 0) _exit(51);
        if (memcmp(buf, ping, sizeof(ping) - 1) != 0) _exit(52);
        if (write_all(sv[1], pong, sizeof(pong) - 1) != 0) _exit(53);
        close(sv[1]);
        _exit(0);
    }
    close(sv[1]);
    if (write_all(sv[0], ping, sizeof(ping) - 1) != 0) return fail("unix", "write-ping");
    memset(buf, 0, sizeof(buf));
    if (read_exact(sv[0], buf, sizeof(pong) - 1) != 0) return fail("unix", "read-pong");
    if (memcmp(buf, pong, sizeof(pong) - 1) != 0) return fail("unix", "compare-pong");
    if (close(sv[0]) != 0) return fail("unix", "close");
    if (wait_child_ok("unix", pid) != 0) return 1;
    puts("L32_PROBE_UNIX_PASS");
    return 0;
}

static int test_poll(void) {
    int p[2];
    if (pipe(p) != 0) return fail("poll", "pipe");
    pid_t pid = fork();
    if (pid < 0) return fail("poll", "fork");
    if (pid == 0) {
        close(p[0]);
        struct timespec req = { .tv_sec = 0, .tv_nsec = 2000000 };
        while (nanosleep(&req, &req) != 0 && errno == EINTR) {}
        const char byte = 'P';
        if (write_all(p[1], &byte, 1) != 0) _exit(54);
        close(p[1]);
        _exit(0);
    }
    close(p[1]);
    struct pollfd fds = { .fd = p[0], .events = POLLIN, .revents = 0 };
    int rc;
    do { rc = poll(&fds, 1, 1000); } while (rc < 0 && errno == EINTR);
    if (rc != 1 || (fds.revents & POLLIN) == 0) return fail("poll", "poll-ready");
    char byte = 0;
    if (read_exact(p[0], &byte, 1) != 0 || byte != 'P') return fail("poll", "read");
    close(p[0]);
    if (wait_child_ok("poll", pid) != 0) return 1;
    puts("L32_PROBE_POLL_PASS");
    return 0;
}

static int test_futex(void) {
    uint32_t *word = mmap(NULL, 4096, PROT_READ | PROT_WRITE,
                          MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (word == MAP_FAILED) return fail("futex", "mmap-shared");
    *word = 0;
    pid_t pid = fork();
    if (pid < 0) return fail("futex", "fork");
    if (pid == 0) {
        struct timespec req = { .tv_sec = 0, .tv_nsec = 2000000 };
        while (nanosleep(&req, &req) != 0 && errno == EINTR) {}
        __atomic_store_n(word, 1u, __ATOMIC_SEQ_CST);
        long woke = syscall(SYS_futex, word, L32_FUTEX_WAKE, 1, NULL, NULL, 0);
        _exit(woke < 0 ? 55 : 0);
    }
    while (__atomic_load_n(word, __ATOMIC_SEQ_CST) == 0u) {
        struct timespec timeout = { .tv_sec = 1, .tv_nsec = 0 };
        long rc = syscall(SYS_futex, word, L32_FUTEX_WAIT, 0, &timeout, NULL, 0);
        if (rc < 0 && errno != EAGAIN && errno != EINTR) return fail("futex", "wait");
    }
    if (__atomic_load_n(word, __ATOMIC_SEQ_CST) != 1u) return fail("futex", "value");
    if (wait_child_ok("futex", pid) != 0) return 1;
    if (munmap(word, 4096) != 0) return fail("futex", "munmap");
    puts("L32_PROBE_FUTEX_PASS");
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s vfs|fd|dir|vm|cow|signal|time|unix|poll|futex\n", argv[0]);
        return 2;
    }
    if (strcmp(argv[1], "vfs") == 0) return test_vfs();
    if (strcmp(argv[1], "fd") == 0) return test_fd();
    if (strcmp(argv[1], "dir") == 0) return test_dir();
    if (strcmp(argv[1], "vm") == 0) return test_vm();
    if (strcmp(argv[1], "cow") == 0) return test_cow();
    if (strcmp(argv[1], "signal") == 0) return test_signal();
    if (strcmp(argv[1], "time") == 0) return test_time();
    if (strcmp(argv[1], "unix") == 0) return test_unix_socket();
    if (strcmp(argv[1], "poll") == 0) return test_poll();
    if (strcmp(argv[1], "futex") == 0) return test_futex();
    fprintf(stderr, "unknown test: %s\n", argv[1]);
    return 2;
}
