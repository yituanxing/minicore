#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <zlib.h>

#define DATA_SIZE 65536u

static int fail(const char *stage, int code) {
    fprintf(stderr, "L32_ZLIB_FAIL stage=%s code=%d errno=%d\n", stage, code, errno);
    return 1;
}

static void fill_data(unsigned char *p, size_t n) {
    static const unsigned char seed[] = "AetherCore-RV32-Linux-zlib";
    uint32_t x = 0x6d2b79f5u;
    size_t i;
    for (i = 0; i < n; ++i) {
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        /* Mix a repeating component with pseudo-random bits so deflate sees
         * both matches and literal-heavy regions. */
        p[i] = (unsigned char)(seed[i % (sizeof(seed) - 1)] ^ (x >> 24) ^ (i & 31u));
    }
}

static int run_mem(void) {
    unsigned char *src = NULL, *compressed = NULL, *roundtrip = NULL;
    uLongf compressed_len, roundtrip_len;
    uLong crc, adler;
    int rc = 1;

    src = (unsigned char *)malloc(DATA_SIZE);
    roundtrip = (unsigned char *)malloc(DATA_SIZE);
    compressed_len = compressBound(DATA_SIZE);
    compressed = (unsigned char *)malloc((size_t)compressed_len);
    if (!src || !compressed || !roundtrip) {
        rc = fail("mem-alloc", Z_MEM_ERROR);
        goto out;
    }
    fill_data(src, DATA_SIZE);

    if (compress2(compressed, &compressed_len, src, DATA_SIZE, 6) != Z_OK) {
        rc = fail("compress2", Z_STREAM_ERROR);
        goto out;
    }
    roundtrip_len = DATA_SIZE;
    if (uncompress(roundtrip, &roundtrip_len, compressed, compressed_len) != Z_OK) {
        rc = fail("uncompress", Z_DATA_ERROR);
        goto out;
    }
    if (roundtrip_len != DATA_SIZE || memcmp(src, roundtrip, DATA_SIZE) != 0) {
        rc = fail("mem-compare", Z_DATA_ERROR);
        goto out;
    }

    crc = crc32(0L, Z_NULL, 0);
    crc = crc32(crc, src, DATA_SIZE);
    adler = adler32(0L, Z_NULL, 0);
    adler = adler32(adler, src, DATA_SIZE);
    if (crc == 0 || adler == 0 || compressed_len == 0 || compressed_len > compressBound(DATA_SIZE)) {
        rc = fail("checksums", Z_DATA_ERROR);
        goto out;
    }

    printf("L32_ZLIB_MEM_DETAIL bytes=%u compressed=%lu crc=%08lx adler=%08lx\n",
           DATA_SIZE, (unsigned long)compressed_len, (unsigned long)crc, (unsigned long)adler);
    puts("L32_ZLIB_MEM_PASS");
    rc = 0;
out:
    free(roundtrip);
    free(compressed);
    free(src);
    return rc;
}

static int run_stream(void) {
    unsigned char *src = NULL, *compressed = NULL, *roundtrip = NULL;
    size_t in_off = 0, compressed_off = 0, decoded_off = 0;
    size_t compressed_cap;
    z_stream zs;
    int zr = Z_OK;
    int rc = 1;

    src = (unsigned char *)malloc(DATA_SIZE);
    roundtrip = (unsigned char *)malloc(DATA_SIZE);
    compressed_cap = (size_t)compressBound(DATA_SIZE);
    compressed = (unsigned char *)malloc(compressed_cap);
    if (!src || !compressed || !roundtrip) {
        rc = fail("stream-alloc", Z_MEM_ERROR);
        goto out;
    }
    fill_data(src, DATA_SIZE);

    memset(&zs, 0, sizeof(zs));
    if (deflateInit(&zs, 6) != Z_OK) {
        rc = fail("deflateInit", Z_STREAM_ERROR);
        goto out;
    }
    while (in_off < DATA_SIZE) {
        size_t chunk = DATA_SIZE - in_off;
        int flush;
        if (chunk > 257u) chunk = 257u;
        zs.next_in = src + in_off;
        zs.avail_in = (uInt)chunk;
        in_off += chunk;
        flush = in_off == DATA_SIZE ? Z_FINISH : Z_NO_FLUSH;
        do {
            size_t room = compressed_cap - compressed_off;
            size_t out_chunk = room > 193u ? 193u : room;
            if (out_chunk == 0) {
                deflateEnd(&zs);
                rc = fail("deflate-capacity", Z_BUF_ERROR);
                goto out;
            }
            zs.next_out = compressed + compressed_off;
            zs.avail_out = (uInt)out_chunk;
            zr = deflate(&zs, flush);
            if (zr != Z_OK && zr != Z_STREAM_END) {
                deflateEnd(&zs);
                rc = fail("deflate", zr);
                goto out;
            }
            compressed_off += out_chunk - zs.avail_out;
        } while (zs.avail_in != 0 || zs.avail_out == 0 || (flush == Z_FINISH && zr != Z_STREAM_END));
    }
    if (zr != Z_STREAM_END || deflateEnd(&zs) != Z_OK) {
        rc = fail("deflateEnd", zr);
        goto out;
    }

    memset(&zs, 0, sizeof(zs));
    if (inflateInit(&zs) != Z_OK) {
        rc = fail("inflateInit", Z_STREAM_ERROR);
        goto out;
    }
    in_off = 0;
    zr = Z_OK;
    while (zr != Z_STREAM_END) {
        if (zs.avail_in == 0 && in_off < compressed_off) {
            size_t chunk = compressed_off - in_off;
            if (chunk > 211u) chunk = 211u;
            zs.next_in = compressed + in_off;
            zs.avail_in = (uInt)chunk;
            in_off += chunk;
        }
        if (decoded_off >= DATA_SIZE) {
            inflateEnd(&zs);
            rc = fail("inflate-capacity", Z_BUF_ERROR);
            goto out;
        }
        {
            size_t room = DATA_SIZE - decoded_off;
            size_t out_chunk = room > 127u ? 127u : room;
            zs.next_out = roundtrip + decoded_off;
            zs.avail_out = (uInt)out_chunk;
            zr = inflate(&zs, Z_NO_FLUSH);
            if (zr != Z_OK && zr != Z_STREAM_END) {
                inflateEnd(&zs);
                rc = fail("inflate", zr);
                goto out;
            }
            decoded_off += out_chunk - zs.avail_out;
        }
        if (zs.avail_in == 0 && in_off == compressed_off && zr == Z_OK && decoded_off < DATA_SIZE) {
            inflateEnd(&zs);
            rc = fail("inflate-truncated", Z_DATA_ERROR);
            goto out;
        }
    }
    if (inflateEnd(&zs) != Z_OK || decoded_off != DATA_SIZE || memcmp(src, roundtrip, DATA_SIZE) != 0) {
        rc = fail("stream-compare", Z_DATA_ERROR);
        goto out;
    }

    printf("L32_ZLIB_STREAM_DETAIL bytes=%u compressed=%zu\n", DATA_SIZE, compressed_off);
    puts("L32_ZLIB_STREAM_PASS");
    rc = 0;
out:
    free(roundtrip);
    free(compressed);
    free(src);
    return rc;
}

static int run_gzfile(void) {
    static const char path[] = "/tmp/l32-zlib-smoke.gz";
    unsigned char *src = NULL, *roundtrip = NULL;
    size_t off = 0;
    gzFile f = NULL;
    int rc = 1;

    src = (unsigned char *)malloc(DATA_SIZE);
    roundtrip = (unsigned char *)malloc(DATA_SIZE);
    if (!src || !roundtrip) {
        rc = fail("gz-alloc", Z_MEM_ERROR);
        goto out;
    }
    fill_data(src, DATA_SIZE);

    f = gzopen(path, "wb6");
    if (!f) {
        rc = fail("gzopen-write", Z_ERRNO);
        goto out;
    }
    while (off < DATA_SIZE) {
        size_t chunk = DATA_SIZE - off;
        int wrote;
        if (chunk > 333u) chunk = 333u;
        wrote = gzwrite(f, src + off, (unsigned int)chunk);
        if (wrote != (int)chunk) {
            gzclose(f);
            f = NULL;
            rc = fail("gzwrite", Z_ERRNO);
            goto out;
        }
        off += chunk;
        if (off >= DATA_SIZE / 2 && off - chunk < DATA_SIZE / 2 && gzflush(f, Z_SYNC_FLUSH) != Z_OK) {
            gzclose(f);
            f = NULL;
            rc = fail("gzflush", Z_ERRNO);
            goto out;
        }
    }
    if (gzclose(f) != Z_OK) {
        f = NULL;
        rc = fail("gzclose-write", Z_ERRNO);
        goto out;
    }
    f = NULL;

    f = gzopen(path, "rb");
    if (!f) {
        rc = fail("gzopen-read", Z_ERRNO);
        goto out;
    }
    off = 0;
    while (off < DATA_SIZE) {
        size_t chunk = DATA_SIZE - off;
        int got;
        if (chunk > 251u) chunk = 251u;
        got = gzread(f, roundtrip + off, (unsigned int)chunk);
        if (got <= 0) {
            gzclose(f);
            f = NULL;
            rc = fail("gzread", got);
            goto out;
        }
        off += (size_t)got;
    }
    {
        unsigned char extra;
        if (gzread(f, &extra, 1) != 0) {
            gzclose(f);
            f = NULL;
            rc = fail("gz-eof", Z_DATA_ERROR);
            goto out;
        }
    }
    if (gzclose(f) != Z_OK) {
        f = NULL;
        rc = fail("gzclose-read", Z_ERRNO);
        goto out;
    }
    f = NULL;
    if (memcmp(src, roundtrip, DATA_SIZE) != 0) {
        rc = fail("gz-compare", Z_DATA_ERROR);
        goto out;
    }

    puts("L32_ZLIB_GZFILE_PASS");
    rc = 0;
out:
    if (f) gzclose(f);
    unlink(path);
    free(roundtrip);
    free(src);
    return rc;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s mem|stream|gzfile\n", argv[0]);
        return 2;
    }
    if (strcmp(argv[1], "mem") == 0) return run_mem();
    if (strcmp(argv[1], "stream") == 0) return run_stream();
    if (strcmp(argv[1], "gzfile") == 0) return run_gzfile();
    fprintf(stderr, "unknown mode: %s\n", argv[1]);
    return 2;
}
