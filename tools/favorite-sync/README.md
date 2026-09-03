# favorite-sync — backfill, reconciliation and rollback tooling for `article_favorites`

Operator CLI implementing §3 (backfill), §4 (reconciliation/repair) and §6 (rollback /
reverse-backfill) of [`05-data-sync-and-rollback-design.md`](../../docs/microservice-extraction/05-data-sync-and-rollback-design.md)
for the **Favorite** domain (Phase 1). It works on two SQLite files:

| Side | File | Table | Owner |
|---|---|---|---|
| `--source` (monolith) | `dev.db` | `article_favorites` | Conduit monolith (Flyway `V1__create_tables.sql`) |
| `--target` (service) | `favorite.db` | `article_favorites` | `favorite-service` (its Flyway `V1__create_favorite_tables.sql`) |

The tool never creates the schema: if a file or the `article_favorites` table is missing it exits
with code 2 and a message telling you to run the owning application's Flyway migration first.

It is a standalone Gradle project. The monolith's root build does **not** include it (there is no
`settings.gradle` at the repo root and this directory has its own), so `./gradlew build` at the
repo root is unaffected.

## Why a Java CLI rather than bash + `sqlite3`

- The monolith and `favorite-service` both use `org.xerial:sqlite-jdbc:3.36.0.3`; using the same
  driver here guarantees identical text/collation semantics on both sides.
- The `sqlite3` binary is not guaranteed on the hosts (in this repo's dev image it only exists as
  part of the Android SDK), while JDK 11 is a hard requirement already.
- Restartable chunked writes, merge-join diffing, the exact JSON report format of 05 §4.2 and the
  `--max-repair` safety guard are tedious and brittle in shell; JUnit 5 gives us a real test suite
  (24 tests: idempotency, crash-restart, drift detection, repair convergence, reverse-backfill,
  empty tables, 10k-row performance).
- No Spring Boot: start-up is instant and the jar has two dependencies (sqlite-jdbc, Jackson).

## Build

Requires **JDK 11** (Spotless/google-java-format fails on 17+):

```sh
export JAVA_HOME=/path/to/jdk-11            # e.g. $HOME/jdk-11.0.32.1+1
cd tools/favorite-sync
./gradlew build                              # compiles, runs Spotless check + 24 JUnit tests
./gradlew installDist                        # -> build/install/favorite-sync/bin/favorite-sync
alias favorite-sync="$PWD/build/install/favorite-sync/bin/favorite-sync"
```

`./gradlew spotlessApply` formats the sources.

## Commands

All commands take `--source <monolith db>` and `--target <service db>`. `--key value` and
`--key=value` are both accepted.

### `backfill`

```sh
favorite-sync backfill --source dev.db --target favorite.db [--chunk 5000]
```

1. Records **T0** (wall clock, printed as `backfill T0=...`). Enable `extraction.favorite.write=dual-write`
   *before* running so every row written after T0 reaches the service via the mirror.
2. Takes an online-backup snapshot of `--source` (SQLite `BACKUP TO`, WAL-safe — the live
   `dev.db` is never scanned while the monolith writes to it).
3. Walks the snapshot in keyset order on `(article_id, user_id)`, `--chunk` rows at a time
   (default 5000), and applies each chunk to `--target` as one `INSERT OR IGNORE` transaction.

Idempotent and restartable: a crash mid-chunk rolls that chunk back; re-running skips everything
already present. Output:

```
backfill source=dev.db target=favorite.db chunk=5000
backfill T0=2026-09-03T04:00:00.123Z
backfill snapshot=/tmp/favorite-sync-snapshot-123.db
backfill sourceRows=128431 targetRowsBefore=0
backfill chunk=1 rows=5000 inserted=5000 skipped=0 lastKey=article-0421|user-17
...
backfill done rowsRead=128431 rowsInserted=128431 rowsSkipped=0 chunks=26 targetRowsAfter=128431 T0=2026-09-03T04:00:00.123Z
```

Exit code 0 on success, 2 on error.

### `reverse-backfill`

```sh
favorite-sync reverse-backfill --source dev.db --target favorite.db [--chunk 5000]
```

Exactly `backfill` with source and target swapped: copies `favorite.db -> dev.db`. Used when
rolling back from state C (service authoritative) — rows that only exist in the service are
inserted into the monolith; rows already in the monolith are untouched (`INSERT OR IGNORE`).

### `reconcile`

```sh
favorite-sync reconcile --source dev.db --target favorite.db \
    [--report out.json] [--repair none|to-target|to-source] [--delete-extras] \
    [--authoritative monolith|service] [--max-repair N]
```

Streams both key sets in `(article_id, user_id)` order and merge-joins them (memory bounded by the
drift, not by the table size). Buckets:

- `missingInService` — key in `--source` but not in `--target`
- `extraInService` — key in `--target` but not in `--source`
- `diverged` — always empty (`article_favorites` has no mutable payload)

Note the report's `monolithCount`/`serviceCount`/`missingInService`/`extraInService` are always
named from the point of view *source = monolith, target = service*, regardless of `--repair` or
`--authoritative`. **Do not swap `--source`/`--target` for reconcile** — use `--repair to-source`
and `--authoritative service` instead.

| Option | Effect |
|---|---|
| (none) | Report-only. Exit **1** if drift > 0, else 0 — suitable for cron/CI gating. |
| `--report out.json` | Also write the 05 §4.2 JSON document (directories are created). |
| `--repair to-target` | `INSERT OR IGNORE` every `missingInService` row into the service DB (monolith authoritative, states A/B). |
| `--repair to-source` | `INSERT OR IGNORE` every `extraInService` row into the monolith DB (service authoritative, rollback from state C). |
| `--delete-extras` | Only with `--repair`: additionally delete rows that the authoritative side does not have (`extraInService` for `to-target`, `missingInService` for `to-source`). **Never deletes without this flag.** |
| `--authoritative` | Label written to the report (`"authoritative"` field). Default `monolith`. |
| `--max-repair N` | Abort (exit 2, nothing written) if a repair would touch more than N rows. Default `max(1000, 1 % of authoritative rows)` per 05 §4.3; `0` disables the guard. |

After a repair the diff is recomputed; the exit code is 0 only when the *remaining* drift is zero.

Report (format of 05 §4.2, plus checksums and, when repairing, a `repair` block):

```json
{
  "runId" : "2026-09-03T04:00:00Z",
  "domain" : "favorite",
  "authoritative" : "monolith",
  "graceSeconds" : 0,
  "tables" : [ {
    "table" : "article_favorites",
    "monolithCount" : 128431,
    "serviceCount" : 128429,
    "monolithChecksum" : "<sha256 of the ordered key stream>",
    "serviceChecksum" : "<sha256 of the ordered key stream>",
    "missingInService" : [ { "articleId" : "article-3", "userId" : "user-7" } ],
    "missingInServiceTotal" : 1,
    "extraInService" : [ ],
    "extraInServiceTotal" : 0,
    "diverged" : [ ],
    "status" : "DRIFT"
  } ],
  "summary" : { "drift" : 1, "clean" : 0, "driftRows" : 1, "status" : "DRIFT" },
  "repair" : { "mode" : "to-target", "deleteExtras" : false, "inserted" : 1, "deleted" : 0,
               "monolithCountAfter" : 128431, "serviceCountAfter" : 128430,
               "missingInServiceAfter" : 0, "extraInServiceAfter" : 0 }
}
```

Key lists are truncated at 1000 entries per bucket; `<bucket>Total` always holds the full count and
`"truncated": true` is set on the table when truncation happened. Repair always acts on the full
set, not on the truncated list. Two identical tables always produce identical checksums.

One-line summaries are printed for logs/alerts:

```
reconcile domain=favorite table=article_favorites phase=before monolith=128431 service=128429 missing=2 extra=0 diverged=0 status=DRIFT
```

`graceSeconds` is always `0`: `article_favorites` has no timestamp column, so the 60 s in-flight
grace of 05 §4.1 cannot be applied per row. Run reconcile against a quiet system, or treat a single
transient `DRIFT` followed by `CLEAN` as in-flight dual-write, not as a defect.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | success; for `reconcile`, zero remaining drift |
| 1 | `reconcile`: drift remains (report-only, or after a repair that was not allowed to delete) |
| 2 | usage error, missing file/table, `--max-repair` guard tripped, SQLite error |

## Tests

```sh
cd tools/favorite-sync && ./gradlew test
```

`BackfillTest` (8): full copy, idempotent re-run, crash after chunk 2 then restart converges,
empty source, target-only rows preserved, missing table / missing file errors, 10k rows.
`ReconcileTest` (11): missing+extra detection and JSON report shape, identical/empty tables clean,
repair to-target with and without `--delete-extras`, repair to-source, `--max-repair` guard and
default, 1000-entry truncation, 10k rows.
`MainTest` (5): exit codes, `backfill` then `reconcile` clean, `reverse-backfill` preserves
service-only rows, argument errors, missing table message.

## Design notes / open questions

- **Direct DB writes for repair.** 05 §4.3 prefers repairing through the service's internal API so
  there is a single write path. This slice implements repair as direct `INSERT OR IGNORE` /
  `DELETE` on the SQLite file (as specified for the tooling) — acceptable for a two-column
  key-only table with no service-side validation, but the service must be stopped or the file
  must not be under concurrent writes while `--repair` runs (SQLite locking makes concurrent
  writes safe but not *coordinated*). Revisit if favorite-service gains validation logic.
- **Standalone job reads both files** (05 §9 Q7). The backfill only reads a `.backup` snapshot of
  `dev.db`; `reconcile` reads both live files read-only unless repairing. If "no shared SQLite"
  is enforced strictly, run reconcile against `.backup` copies of both files.
- `--authoritative` is a report label only; it does not change which side is repaired. This is
  deliberate so that a typo can never silently flip the repair direction.
