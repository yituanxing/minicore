#include "sqlite3.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void die(sqlite3 *db, const char *step, int rc) {
    fprintf(stderr, "L32_SQLITE_REAL_FAIL step=%s rc=%d msg=%s\n",
            step, rc, db ? sqlite3_errmsg(db) : "no-db");
    if (db) sqlite3_close(db);
    exit(1);
}

static void exec_ok(sqlite3 *db, const char *sql, const char *step) {
    char *err = NULL;
    int rc = sqlite3_exec(db, sql, NULL, NULL, &err);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "L32_SQLITE_REAL_FAIL step=%s rc=%d err=%s\n",
                step, rc, err ? err : "none");
        sqlite3_free(err);
        sqlite3_close(db);
        exit(1);
    }
}

int main(void) {
    const char *path = "/tmp/l32-sqlite-real.db";
    sqlite3 *db = NULL;
    sqlite3_stmt *stmt = NULL;
    long long count = 0, sum = 0, sumsq = 0;
    int rc;

    unlink(path);
    rc = sqlite3_open(path, &db);
    if (rc != SQLITE_OK) die(db, "open", rc);

    exec_ok(db, "PRAGMA journal_mode=DELETE;", "journal");
    exec_ok(db, "CREATE TABLE t(x INTEGER NOT NULL, s TEXT NOT NULL);", "create");
    exec_ok(db, "BEGIN IMMEDIATE;", "begin");

    rc = sqlite3_prepare_v2(db, "INSERT INTO t(x,s) VALUES(?1,?2);", -1, &stmt, NULL);
    if (rc != SQLITE_OK) die(db, "prepare-insert", rc);
    for (int i = 1; i <= 1000; ++i) {
        char text[32];
        snprintf(text, sizeof(text), "row-%04d", i);
        sqlite3_bind_int(stmt, 1, i);
        sqlite3_bind_text(stmt, 2, text, -1, SQLITE_TRANSIENT);
        rc = sqlite3_step(stmt);
        if (rc != SQLITE_DONE) die(db, "insert-step", rc);
        sqlite3_reset(stmt);
        sqlite3_clear_bindings(stmt);
    }
    sqlite3_finalize(stmt);
    stmt = NULL;
    exec_ok(db, "COMMIT;", "commit");
    exec_ok(db, "CREATE INDEX t_x ON t(x);", "index");

    rc = sqlite3_close(db);
    db = NULL;
    if (rc != SQLITE_OK) die(NULL, "close-first", rc);

    rc = sqlite3_open(path, &db);
    if (rc != SQLITE_OK) die(db, "reopen", rc);
    rc = sqlite3_prepare_v2(db,
        "SELECT count(*),sum(x),sum(x*x) FROM t WHERE x BETWEEN 1 AND 1000;",
        -1, &stmt, NULL);
    if (rc != SQLITE_OK) die(db, "prepare-aggregate", rc);
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW) die(db, "aggregate-step", rc);
    count = sqlite3_column_int64(stmt, 0);
    sum = sqlite3_column_int64(stmt, 1);
    sumsq = sqlite3_column_int64(stmt, 2);
    sqlite3_finalize(stmt);
    stmt = NULL;
    if (count != 1000 || sum != 500500 || sumsq != 333833500) {
        fprintf(stderr, "L32_SQLITE_REAL_FAIL step=aggregate-values got=%lld,%lld,%lld\n",
                count, sum, sumsq);
        sqlite3_close(db);
        return 1;
    }

    rc = sqlite3_prepare_v2(db, "PRAGMA integrity_check;", -1, &stmt, NULL);
    if (rc != SQLITE_OK) die(db, "prepare-integrity", rc);
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW) die(db, "integrity-step", rc);
    const unsigned char *integrity = sqlite3_column_text(stmt, 0);
    if (!integrity || strcmp((const char *)integrity, "ok") != 0) {
        sqlite3_finalize(stmt);
        die(db, "integrity-value", SQLITE_CORRUPT);
    }
    sqlite3_finalize(stmt);
    rc = sqlite3_close(db);
    db = NULL;
    if (rc != SQLITE_OK) die(NULL, "close-final", rc);
    unlink(path);

    printf("L32_SQLITE_REAL_PASS %lld %lld %lld ok\n", count, sum, sumsq);
    return 0;
}
