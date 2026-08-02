/* SPDX-License-Identifier: MIT */

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "lfs.h"

#define FLASH_BLOCK_SIZE 256u
#define FLASH_BLOCK_COUNT 128u
#define FLASH_BYTES (FLASH_BLOCK_SIZE * FLASH_BLOCK_COUNT)
#define CACHE_SIZE 64u
#define LOOKAHEAD_SIZE 16u
#define PRIMARY_SIZE 700u
#define TRUNCATED_SIZE 513u
#define APPEND_SIZE 73u
#define FINAL_PRIMARY_SIZE (TRUNCATED_SIZE + APPEND_SIZE)
#define UART_ADDR ((volatile uint8_t *)0x10000000u)

struct ram_flash {
    uint8_t storage[FLASH_BYTES];
    uint32_t read_ops;
    uint32_t prog_ops;
    uint32_t erase_ops;
    uint32_t sync_ops;
    uint32_t read_bytes;
    uint32_t prog_bytes;
};

static struct ram_flash device;
static uint8_t read_cache[CACHE_SIZE];
static uint8_t prog_cache[CACHE_SIZE];
static uint8_t lookahead_cache[LOOKAHEAD_SIZE];
static uint8_t file_cache[CACHE_SIZE];
static uint8_t primary_data[PRIMARY_SIZE];
static uint8_t expected_data[FINAL_PRIMARY_SIZE];
static uint8_t scratch[PRIMARY_SIZE];

static const uint8_t note_data[] =
    "AetherCore littlefs state survives close, unmount, and remount.";

static void uart_putc(char value) {
    *UART_ADDR = (uint8_t)value;
}

static void uart_puts(const char *text) {
    while (*text != '\0') uart_putc(*text++);
}

static void uart_put_u32(uint32_t value) {
    char digits[10];
    unsigned count = 0;
    do {
        digits[count++] = (char)('0' + (value % 10u));
        value /= 10u;
    } while (value != 0u);
    while (count != 0u) uart_putc(digits[--count]);
}

static void uart_put_i32(int32_t value) {
    if (value < 0) {
        uart_putc('-');
        uart_put_u32((uint32_t)(-(value + 1)) + 1u);
    } else {
        uart_put_u32((uint32_t)value);
    }
}

static void uart_put_hex32(uint32_t value) {
    static const char hex[] = "0123456789abcdef";
    for (int shift = 28; shift >= 0; shift -= 4) {
        uart_putc(hex[(value >> (unsigned)shift) & 0xfu]);
    }
}

static int fail(unsigned stage, int error) {
    uart_puts("LFS_FAIL stage=");
    uart_put_u32(stage);
    uart_puts(" err=");
    uart_put_i32(error);
    uart_putc('\n');
    return 1;
}

static int checked_region(lfs_block_t block, lfs_off_t off, lfs_size_t size) {
    if (block >= FLASH_BLOCK_COUNT) return 0;
    if (off > FLASH_BLOCK_SIZE) return 0;
    if (size > FLASH_BLOCK_SIZE - off) return 0;
    return 1;
}

static int flash_read(const struct lfs_config *cfg, lfs_block_t block,
        lfs_off_t off, void *buffer, lfs_size_t size) {
    struct ram_flash *flash = (struct ram_flash *)cfg->context;
    if (!checked_region(block, off, size)) return LFS_ERR_IO;
    if ((off % cfg->read_size) != 0u || (size % cfg->read_size) != 0u) {
        return LFS_ERR_IO;
    }

    memcpy(buffer, &flash->storage[block * FLASH_BLOCK_SIZE + off], size);
    flash->read_ops += 1u;
    flash->read_bytes += size;
    return 0;
}

static int flash_prog(const struct lfs_config *cfg, lfs_block_t block,
        lfs_off_t off, const void *buffer, lfs_size_t size) {
    struct ram_flash *flash = (struct ram_flash *)cfg->context;
    const uint8_t *source = (const uint8_t *)buffer;
    if (!checked_region(block, off, size)) return LFS_ERR_IO;
    if ((off % cfg->prog_size) != 0u || (size % cfg->prog_size) != 0u) {
        return LFS_ERR_IO;
    }

    uint8_t *target = &flash->storage[block * FLASH_BLOCK_SIZE + off];
    for (lfs_size_t i = 0; i < size; ++i) {
        if ((target[i] & source[i]) != source[i]) return LFS_ERR_CORRUPT;
    }
    for (lfs_size_t i = 0; i < size; ++i) target[i] &= source[i];

    flash->prog_ops += 1u;
    flash->prog_bytes += size;
    return 0;
}

static int flash_erase(const struct lfs_config *cfg, lfs_block_t block) {
    struct ram_flash *flash = (struct ram_flash *)cfg->context;
    if (block >= FLASH_BLOCK_COUNT) return LFS_ERR_IO;
    memset(&flash->storage[block * FLASH_BLOCK_SIZE], 0xff, FLASH_BLOCK_SIZE);
    flash->erase_ops += 1u;
    return 0;
}

static int flash_sync(const struct lfs_config *cfg) {
    struct ram_flash *flash = (struct ram_flash *)cfg->context;
    flash->sync_ops += 1u;
    return 0;
}

static uint32_t crc32(const void *buffer, size_t size) {
    const uint8_t *bytes = (const uint8_t *)buffer;
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < size; ++i) {
        crc ^= bytes[i];
        for (unsigned bit = 0; bit < 8u; ++bit) {
            uint32_t mask = 0u - (crc & 1u);
            crc = (crc >> 1) ^ (0xedb88320u & mask);
        }
    }
    return ~crc;
}

static void fill_data(void) {
    for (uint32_t i = 0; i < PRIMARY_SIZE; ++i) {
        primary_data[i] = (uint8_t)(0x5au + i * 37u + (i >> 1) * 11u);
    }
    memcpy(expected_data, primary_data, TRUNCATED_SIZE);
    for (uint32_t i = 0; i < APPEND_SIZE; ++i) {
        expected_data[TRUNCATED_SIZE + i] = (uint8_t)(0xa5u ^ (i * 29u));
    }
}

static int verify_directory(lfs_t *lfs) {
    lfs_dir_t dir;
    struct lfs_info info;
    unsigned files = 0;
    unsigned archive_seen = 0;
    unsigned note_seen = 0;

    memset(&dir, 0, sizeof(dir));
    int result = lfs_dir_open(lfs, &dir, "/data");
    if (result < 0) return result;

    for (;;) {
        result = lfs_dir_read(lfs, &dir, &info);
        if (result < 0) {
            lfs_dir_close(lfs, &dir);
            return result;
        }
        if (result == 0) break;
        if (strcmp(info.name, ".") == 0 || strcmp(info.name, "..") == 0) continue;

        files += 1u;
        if (strcmp(info.name, "archive.bin") == 0) {
            if (info.type != LFS_TYPE_REG || info.size != FINAL_PRIMARY_SIZE) {
                lfs_dir_close(lfs, &dir);
                return LFS_ERR_CORRUPT;
            }
            archive_seen += 1u;
        } else if (strcmp(info.name, "note.txt") == 0) {
            if (info.type != LFS_TYPE_REG || info.size != sizeof(note_data) - 1u) {
                lfs_dir_close(lfs, &dir);
                return LFS_ERR_CORRUPT;
            }
            note_seen += 1u;
        } else {
            lfs_dir_close(lfs, &dir);
            return LFS_ERR_CORRUPT;
        }
    }

    result = lfs_dir_close(lfs, &dir);
    if (result < 0) return result;
    return (files == 2u && archive_seen == 1u && note_seen == 1u)
        ? 0 : LFS_ERR_CORRUPT;
}

static int open_file(lfs_t *lfs, lfs_file_t *file,
        const char *path, int flags, struct lfs_file_config *file_cfg) {
    memset(file, 0, sizeof(*file));
    memset(file_cache, 0, sizeof(file_cache));
    memset(file_cfg, 0, sizeof(*file_cfg));
    file_cfg->buffer = file_cache;
    return lfs_file_opencfg(lfs, file, path, flags, file_cfg);
}

int main(void) {
    lfs_t lfs;
    lfs_file_t file;
    struct lfs_file_config file_cfg;
    struct lfs_info info;
    struct lfs_fsinfo fsinfo;
    struct lfs_config cfg;

    memset(&device, 0, sizeof(device));
    memset(device.storage, 0xff, sizeof(device.storage));
    memset(&cfg, 0, sizeof(cfg));
    memset(&lfs, 0, sizeof(lfs));
    fill_data();

    cfg.context = &device;
    cfg.read = flash_read;
    cfg.prog = flash_prog;
    cfg.erase = flash_erase;
    cfg.sync = flash_sync;
    cfg.read_size = 16u;
    cfg.prog_size = 16u;
    cfg.block_size = FLASH_BLOCK_SIZE;
    cfg.block_count = FLASH_BLOCK_COUNT;
    cfg.block_cycles = 100;
    cfg.cache_size = CACHE_SIZE;
    cfg.lookahead_size = LOOKAHEAD_SIZE;
    cfg.read_buffer = read_cache;
    cfg.prog_buffer = prog_cache;
    cfg.lookahead_buffer = lookahead_cache;
    cfg.name_max = 63u;
    cfg.file_max = 4096u;
    cfg.inline_max = (lfs_size_t)-1;

    int result = lfs_format(&lfs, &cfg);
    if (result < 0) return fail(1u, result);
    result = lfs_mount(&lfs, &cfg);
    if (result < 0) return fail(2u, result);

    result = lfs_fs_stat(&lfs, &fsinfo);
    if (result < 0) return fail(3u, result);
    if (fsinfo.block_size != FLASH_BLOCK_SIZE ||
            fsinfo.block_count != FLASH_BLOCK_COUNT ||
            fsinfo.name_max != 63u || fsinfo.file_max != 4096u) {
        return fail(4u, LFS_ERR_CORRUPT);
    }

    result = lfs_mkdir(&lfs, "/data");
    if (result < 0) return fail(5u, result);

    result = open_file(&lfs, &file, "/data/alpha.bin",
            LFS_O_CREAT | LFS_O_RDWR, &file_cfg);
    if (result < 0) return fail(6u, result);
    lfs_ssize_t transferred = lfs_file_write(&lfs, &file,
            primary_data, PRIMARY_SIZE);
    if (transferred != (lfs_ssize_t)PRIMARY_SIZE) return fail(7u, (int)transferred);
    lfs_soff_t position = lfs_file_seek(&lfs, &file, 123, LFS_SEEK_SET);
    if (position != 123) return fail(8u, (int)position);
    transferred = lfs_file_read(&lfs, &file, scratch, 211u);
    if (transferred != 211 || memcmp(scratch, &primary_data[123], 211u) != 0) {
        return fail(9u, (int)transferred);
    }
    result = lfs_file_close(&lfs, &file);
    if (result < 0) return fail(10u, result);

    result = open_file(&lfs, &file, "/data/note.txt",
            LFS_O_CREAT | LFS_O_WRONLY, &file_cfg);
    if (result < 0) return fail(11u, result);
    transferred = lfs_file_write(&lfs, &file, note_data, sizeof(note_data) - 1u);
    if (transferred != (lfs_ssize_t)(sizeof(note_data) - 1u)) {
        return fail(12u, (int)transferred);
    }
    result = lfs_file_close(&lfs, &file);
    if (result < 0) return fail(13u, result);

    result = lfs_rename(&lfs, "/data/alpha.bin", "/data/archive.bin");
    if (result < 0) return fail(14u, result);

    result = open_file(&lfs, &file, "/data/archive.bin", LFS_O_RDWR, &file_cfg);
    if (result < 0) return fail(15u, result);
    result = lfs_file_truncate(&lfs, &file, TRUNCATED_SIZE);
    if (result < 0) return fail(16u, result);
    position = lfs_file_seek(&lfs, &file, 0, LFS_SEEK_END);
    if (position != (lfs_soff_t)TRUNCATED_SIZE) return fail(17u, (int)position);
    transferred = lfs_file_write(&lfs, &file,
            &expected_data[TRUNCATED_SIZE], APPEND_SIZE);
    if (transferred != (lfs_ssize_t)APPEND_SIZE) return fail(18u, (int)transferred);
    result = lfs_file_close(&lfs, &file);
    if (result < 0) return fail(19u, result);

    result = verify_directory(&lfs);
    if (result < 0) return fail(20u, result);

    lfs_ssize_t used_before = lfs_fs_size(&lfs);
    if (used_before <= 0) return fail(21u, (int)used_before);

    result = lfs_unmount(&lfs);
    if (result < 0) return fail(22u, result);
    uint32_t crc_before = crc32(device.storage, sizeof(device.storage));

    memset(&lfs, 0, sizeof(lfs));
    memset(read_cache, 0, sizeof(read_cache));
    memset(prog_cache, 0, sizeof(prog_cache));
    memset(lookahead_cache, 0, sizeof(lookahead_cache));
    result = lfs_mount(&lfs, &cfg);
    if (result < 0) return fail(23u, result);

    result = lfs_stat(&lfs, "/data/archive.bin", &info);
    if (result < 0 || info.type != LFS_TYPE_REG || info.size != FINAL_PRIMARY_SIZE) {
        return fail(24u, result < 0 ? result : LFS_ERR_CORRUPT);
    }
    result = lfs_stat(&lfs, "/data/note.txt", &info);
    if (result < 0 || info.type != LFS_TYPE_REG || info.size != sizeof(note_data) - 1u) {
        return fail(25u, result < 0 ? result : LFS_ERR_CORRUPT);
    }

    result = open_file(&lfs, &file, "/data/archive.bin", LFS_O_RDONLY, &file_cfg);
    if (result < 0) return fail(26u, result);
    transferred = lfs_file_read(&lfs, &file, scratch, FINAL_PRIMARY_SIZE);
    if (transferred != (lfs_ssize_t)FINAL_PRIMARY_SIZE ||
            memcmp(scratch, expected_data, FINAL_PRIMARY_SIZE) != 0) {
        return fail(27u, (int)transferred);
    }
    result = lfs_file_close(&lfs, &file);
    if (result < 0) return fail(28u, result);

    result = open_file(&lfs, &file, "/data/note.txt", LFS_O_RDONLY, &file_cfg);
    if (result < 0) return fail(29u, result);
    transferred = lfs_file_read(&lfs, &file, scratch, sizeof(note_data) - 1u);
    if (transferred != (lfs_ssize_t)(sizeof(note_data) - 1u) ||
            memcmp(scratch, note_data, sizeof(note_data) - 1u) != 0) {
        return fail(30u, (int)transferred);
    }
    result = lfs_file_close(&lfs, &file);
    if (result < 0) return fail(31u, result);

    result = verify_directory(&lfs);
    if (result < 0) return fail(32u, result);
    lfs_ssize_t used_after = lfs_fs_size(&lfs);
    if (used_after != used_before) return fail(33u, (int)used_after);

    result = lfs_unmount(&lfs);
    if (result < 0) return fail(34u, result);
    uint32_t crc_after = crc32(device.storage, sizeof(device.storage));
    if (crc_after != crc_before) return fail(35u, LFS_ERR_CORRUPT);

    uart_puts("LFS_PASS read_ops=");
    uart_put_u32(device.read_ops);
    uart_puts(" prog_ops=");
    uart_put_u32(device.prog_ops);
    uart_puts(" erase_ops=");
    uart_put_u32(device.erase_ops);
    uart_puts(" sync_ops=");
    uart_put_u32(device.sync_ops);
    uart_puts(" read_bytes=");
    uart_put_u32(device.read_bytes);
    uart_puts(" prog_bytes=");
    uart_put_u32(device.prog_bytes);
    uart_puts(" used_blocks=");
    uart_put_u32((uint32_t)used_after);
    uart_puts(" image_crc32=");
    uart_put_hex32(crc_after);
    uart_putc('\n');
    return 0;
}
