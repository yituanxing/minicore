#include <png.h>
#include <zlib.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define WIDTH 37u
#define HEIGHT 29u

static void die(const char *message) {
    fprintf(stderr, "L32_LIBPNG_ERROR %s\n", message);
    exit(1);
}

static void png_silent_error(png_structp png_ptr, png_const_charp message) {
    (void)message;
    png_longjmp(png_ptr, 1);
}

static void png_silent_warning(png_structp png_ptr, png_const_charp message) {
    (void)png_ptr;
    (void)message;
}

static void fill_row(png_bytep row, png_uint_32 y) {
    png_uint_32 x;
    for (x = 0; x < WIDTH; ++x) {
        row[x * 3u + 0u] = (png_byte)((x * 7u + y * 3u) & 0xffu);
        row[x * 3u + 1u] = (png_byte)((x * 5u + y * 11u) & 0xffu);
        row[x * 3u + 2u] = (png_byte)(((x * 13u) ^ (y * 17u)) & 0xffu);
    }
}

static int write_png(const char *path) {
    FILE *fp = fopen(path, "wb");
    png_structp png_ptr = NULL;
    png_infop info_ptr = NULL;
    png_bytep row = NULL;
    png_uint_32 y;
    int rc = -1;

    if (fp == NULL)
        return -1;
    png_ptr = png_create_write_struct(PNG_LIBPNG_VER_STRING, NULL, png_silent_error, png_silent_warning);
    if (png_ptr == NULL)
        goto out;
    info_ptr = png_create_info_struct(png_ptr);
    if (info_ptr == NULL)
        goto out;
    if (setjmp(png_jmpbuf(png_ptr)) != 0)
        goto out;

    png_init_io(png_ptr, fp);
    png_set_IHDR(png_ptr, info_ptr, WIDTH, HEIGHT, 8, PNG_COLOR_TYPE_RGB,
                 PNG_INTERLACE_NONE, PNG_COMPRESSION_TYPE_BASE, PNG_FILTER_TYPE_BASE);
    png_set_filter(png_ptr, PNG_FILTER_TYPE_BASE, PNG_ALL_FILTERS);
    png_write_info(png_ptr, info_ptr);

    row = malloc(WIDTH * 3u);
    if (row == NULL)
        goto out;
    for (y = 0; y < HEIGHT; ++y) {
        fill_row(row, y);
        png_write_row(png_ptr, row);
    }
    png_write_end(png_ptr, info_ptr);
    rc = 0;

out:
    free(row);
    if (png_ptr != NULL)
        png_destroy_write_struct(&png_ptr, info_ptr != NULL ? &info_ptr : NULL);
    fclose(fp);
    return rc;
}

static int read_png_and_verify(const char *path) {
    FILE *fp = fopen(path, "rb");
    png_structp png_ptr = NULL;
    png_infop info_ptr = NULL;
    png_bytep row = NULL;
    png_bytep expected = NULL;
    png_uint_32 width = 0, height = 0, y;
    int bit_depth = 0, color_type = 0;
    int rc = -1;

    if (fp == NULL)
        return -1;
    png_ptr = png_create_read_struct(PNG_LIBPNG_VER_STRING, NULL, png_silent_error, png_silent_warning);
    if (png_ptr == NULL)
        goto out;
    info_ptr = png_create_info_struct(png_ptr);
    if (info_ptr == NULL)
        goto out;
    if (setjmp(png_jmpbuf(png_ptr)) != 0)
        goto out;

    png_init_io(png_ptr, fp);
    png_read_info(png_ptr, info_ptr);
    width = png_get_image_width(png_ptr, info_ptr);
    height = png_get_image_height(png_ptr, info_ptr);
    bit_depth = png_get_bit_depth(png_ptr, info_ptr);
    color_type = png_get_color_type(png_ptr, info_ptr);
    if (width != WIDTH || height != HEIGHT || bit_depth != 8 || color_type != PNG_COLOR_TYPE_RGB)
        goto out;
    png_read_update_info(png_ptr, info_ptr);
    if (png_get_rowbytes(png_ptr, info_ptr) != WIDTH * 3u)
        goto out;

    row = malloc(WIDTH * 3u);
    expected = malloc(WIDTH * 3u);
    if (row == NULL || expected == NULL)
        goto out;
    for (y = 0; y < HEIGHT; ++y) {
        png_read_row(png_ptr, row, NULL);
        fill_row(expected, y);
        if (memcmp(row, expected, WIDTH * 3u) != 0)
            goto out;
    }
    png_read_end(png_ptr, info_ptr);
    rc = 0;

out:
    free(expected);
    free(row);
    if (png_ptr != NULL)
        png_destroy_read_struct(&png_ptr, info_ptr != NULL ? &info_ptr : NULL, NULL);
    fclose(fp);
    return rc;
}

static uint32_t read_be32(const unsigned char *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static int verify_crcs_and_make_bad_copy(const char *path, const char *bad_path) {
    FILE *fp = fopen(path, "rb");
    unsigned char *data = NULL;
    long end;
    size_t size, off = 8u;
    int saw_idat = 0, corrupted = 0, rc = -1;

    if (fp == NULL)
        return -1;
    if (fseek(fp, 0, SEEK_END) != 0)
        goto out;
    end = ftell(fp);
    if (end < 8 || fseek(fp, 0, SEEK_SET) != 0)
        goto out;
    size = (size_t)end;
    data = malloc(size);
    if (data == NULL || fread(data, 1, size, fp) != size)
        goto out;
    if (memcmp(data, "\x89PNG\r\n\x1a\n", 8) != 0)
        goto out;

    while (off + 12u <= size) {
        uint32_t len = read_be32(data + off);
        unsigned char *type = data + off + 4u;
        unsigned char *payload = data + off + 8u;
        unsigned char *crc_ptr;
        uLong calc;
        uint32_t stored;
        size_t chunk_size = (size_t)len + 12u;

        if (chunk_size > size - off)
            goto out;
        crc_ptr = payload + len;
        calc = crc32(0L, Z_NULL, 0);
        calc = crc32(calc, type, 4u);
        calc = crc32(calc, payload, (uInt)len);
        stored = read_be32(crc_ptr);
        if ((uint32_t)calc != stored)
            goto out;

        if (memcmp(type, "IDAT", 4) == 0) {
            saw_idat = 1;
            if (!corrupted) {
                crc_ptr[3] ^= 0x01u;
                corrupted = 1;
            }
        }
        off += chunk_size;
        if (memcmp(type, "IEND", 4) == 0)
            break;
    }
    if (!saw_idat || !corrupted)
        goto out;

    fp = freopen(bad_path, "wb", fp);
    if (fp == NULL)
        return -1;
    if (fwrite(data, 1, size, fp) != size || fflush(fp) != 0)
        goto out;
    rc = 0;

out:
    free(data);
    if (fp != NULL)
        fclose(fp);
    return rc;
}

static int expect_crc_failure(const char *path) {
    FILE *fp = fopen(path, "rb");
    png_structp png_ptr = NULL;
    png_infop info_ptr = NULL;
    png_bytep row = NULL;
    png_uint_32 y;
    int saw_error = 0;

    if (fp == NULL)
        return -1;
    png_ptr = png_create_read_struct(PNG_LIBPNG_VER_STRING, NULL, png_silent_error, png_silent_warning);
    if (png_ptr == NULL)
        goto out;
    info_ptr = png_create_info_struct(png_ptr);
    if (info_ptr == NULL)
        goto out;
    if (setjmp(png_jmpbuf(png_ptr)) != 0) {
        saw_error = 1;
        goto out;
    }

    png_init_io(png_ptr, fp);
    png_read_info(png_ptr, info_ptr);
    png_read_update_info(png_ptr, info_ptr);
    row = malloc(png_get_rowbytes(png_ptr, info_ptr));
    if (row == NULL)
        goto out;
    for (y = 0; y < png_get_image_height(png_ptr, info_ptr); ++y)
        png_read_row(png_ptr, row, NULL);
    png_read_end(png_ptr, info_ptr);

out:
    free(row);
    if (png_ptr != NULL)
        png_destroy_read_struct(&png_ptr, info_ptr != NULL ? &info_ptr : NULL, NULL);
    fclose(fp);
    return saw_error ? 0 : -1;
}

int main(void) {
    const char *path = "/tmp/l32-libpng.png";
    const char *bad_path = "/tmp/l32-libpng-bad.png";

    unlink(path);
    unlink(bad_path);
    if (write_png(path) != 0)
        die("write");
    if (read_png_and_verify(path) != 0)
        die("roundtrip");
    if (verify_crcs_and_make_bad_copy(path, bad_path) != 0)
        die("crc-verify");
    if (expect_crc_failure(bad_path) != 0)
        die("crc-reject");
    if (unlink(path) != 0 || unlink(bad_path) != 0)
        die("unlink");

    printf("L32_LIBPNG_REAL_PASS %u %u CRC_OK\n", WIDTH, HEIGHT);
    return 0;
}
