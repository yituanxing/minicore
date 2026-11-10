#!/usr/bin/env python3
"""Hash a complete staged SD1 rootfs without following guest symlinks.

The official supplier tar SHA256 pins its input archive, but rootfs staging
also changes serial-login configuration, /etc/fstab, and /dev. This digest
pins the *derived* tree which is actually copied onto the SD ext4 partition.
Run via sudo as the staging directory may contain root-only Debian files.
"""

import hashlib
import os
import stat
import sys


def main() -> int:
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        sys.exit("usage: rv64_debian_sd1_tree_digest.py ROOTFS")
    root = os.path.realpath(sys.argv[1])
    digest = hashlib.sha256()
    digest.update(b"aethercore-sd1-rootfs-tree-v1\0")

    def visit(parent: str, rel: str) -> None:
        # Never follow directories that are symlinks, including guest
        # /lib -> /usr/lib aliases. The link target bytes are signed instead.
        with os.scandir(parent) as iterator:
            entries = sorted(iterator, key=lambda e: os.fsencode(e.name))
        for entry in entries:
            name_bytes = os.fsencode(entry.name)
            rel_bytes = (os.fsencode(rel) + b"/" if rel else b"") + name_bytes
            info = entry.stat(follow_symlinks=False)
            mode = info.st_mode
            digest.update(b"entry\0" + len(rel_bytes).to_bytes(8, "little") + rel_bytes)
            digest.update((mode & 0o177777).to_bytes(4, "little"))
            digest.update(info.st_uid.to_bytes(8, "little"))
            digest.update(info.st_gid.to_bytes(8, "little"))
            if stat.S_ISREG(mode):
                digest.update(b"file\0")
                digest.update(info.st_size.to_bytes(8, "little"))
                with open(entry.path, "rb") as inp:
                    while chunk := inp.read(1024 * 1024):
                        digest.update(chunk)
            elif stat.S_ISLNK(mode):
                target = os.fsencode(os.readlink(entry.path))
                digest.update(b"symlink\0" + len(target).to_bytes(8, "little") + target)
            elif stat.S_ISDIR(mode):
                digest.update(b"dir\0")
                visit(entry.path, os.fsdecode(rel_bytes))
            elif stat.S_ISCHR(mode) or stat.S_ISBLK(mode):
                digest.update(b"device\0" + info.st_rdev.to_bytes(8, "little"))
            elif stat.S_ISFIFO(mode):
                digest.update(b"fifo\0")
            else:
                raise ValueError(f"unsupported rootfs node: {rel_bytes!r}")

    visit(root, "")
    print(digest.hexdigest())
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, OverflowError) as exc:
        sys.exit(f"ERROR: cannot hash SD1 rootfs tree: {exc}")
