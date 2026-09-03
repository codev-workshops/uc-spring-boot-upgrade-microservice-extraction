# favorite-sync — backfill, reconciliation and rollback tooling for `article_favorites`, `comments`, tags and `articles`

Operator CLI implementing §3 (backfill), §4 (reconciliation/repair) and §6 (rollback /
reverse-backfill) of [`05-data-sync-and-rollback-design.md`](../../docs/microservice-extraction/05-data-sync-and-rollback-design.md)
for the **Favorite** domain (Phase 1), via `--domain comment` the **Comment** domain (Phase 2),
via `--domain tag` the **Tag** tables of `article-service` (Phase 3) and via `--domain article`
the **`articles`** table of `article-service` (Phase 4).
The project keeps its Phase 1 name; every command takes `--domain favorite|comment|tag|article`
(default `favorite`, so all Phase 1 commands are unchanged). It works on two SQLite files:

| `--domain` | Side | File | Table | Natural key | Compared payload | Owner |
|---|---|---|---|---|---|---|
| `favorite` (default) | `--source` (monolith) | `dev.db` | `article_favorites` | `(article_id, user_id)` | — | Conduit monolith (Flyway `V1__create_tables.sql`) |
| `favorite` (default) | `--target` (service) | `favorite.db` | `article_favorites` | `(article_id, user_id)` | — | `favorite-service` (its Flyway `V1__create_favorite_tables.sql`) |
| `comment` | `--source` (monolith) | `dev.db` | `comments` | `id` | `body, article_id, user_id, created_at, updated_at` | Conduit monolith (Flyway `V1__create_tables.sql`) |
| `comment` | `--target` (service) | `comment-service/comment.db` | `comments` | `id` | `body, article_id, user_id, created_at, updated_at` | `comment-service` (its Flyway `V1__create_comment_tables.sql`) |
| `tag` | `--source` (monolith) | `dev.db` | `tags` **then** `article_tags` | `id` / `(article_id, tag_id)` | `name` / — | Conduit monolith (Flyway `V1__create_tables.sql`) |
| `tag` | `--target` (service) | `article-service/article.db` | `tags` **then** `article_tags` | `id` / `(article_id, tag_id)` | `name` / — | `article-service` (its Flyway migration) |
| `article` | `--source` (monolith) | `dev.db` | `articles` | `id` | `user_id, slug, title, description, body, created_at, updated_at` | Conduit monolith (Flyway `V1__create_tables.sql`) |
| `article` | `--target` (service) | `article-service/article.db` | `articles` | `id` | `user_id, slug, title, description, body, created_at, updated_at` | `article-service` (Flyway `V1__create_article_tables.sql`) |

`--domain tag` is the only multi-table domain: **one** invocation of `backfill`,
`reverse-backfill` or `reconcile` processes `tags` first and `article_tags` second (so a relation
is never copied before the tag it points at) and reports both, per table, in that order.

`--domain article` covers **only** `articles`. The tag tables live in the same `article.db` but
stay the job of `--domain tag`, so a Phase 4 cutover is two invocations per command, **`tag`
first, then `article`** (`article_tags.article_id` has no FK, so the order is about the shadow-read
comparison of `tagList`, not about constraints). A combined `--domain article-bundle` was
deliberately not added — see the open questions.

The tool never creates the schema: if a file or the domain's table is missing it exits with code 2
and a message telling you to run the owning application's Flyway migration first.

For `comment` and `article`, rows are copied **verbatim**: every column is read and written as the
raw SQLite value (`getObject`/`setObject`), so `created_at`/`updated_at` keep exactly the storage
class and value the monolith wrote (INTEGER epoch-millis from `DateTimeHandler`, or TEXT from the
`datetime(...)` seed rows). Nothing is parsed or reformatted; timestamps are compared as stored,
the `updatedAt == createdAt` quirk of `TransferData.xml` is preserved, and so is the
`ArticleMapper.xml#update` quirk of never touching `updated_at`, because both columns are copied
as stored.

`articles.slug` is **UNIQUE** independently of the `id` primary key. `INSERT OR IGNORE` only
protects against a duplicate `id`; a source row whose slug is already held by a *different* id in
the target would either be silently dropped (backfill) or make a repair fail with a constraint
error. The tool therefore checks every row it could not insert / would update against the other
side and reports such rows as **conflicts** (`uniqueConflictsInService` /
`uniqueConflictsInMonolith`, each entry `{ "id", "column": "slug", "value", "conflictingId" }`),
never crashes, and never overwrites the holder. A conflicting row stays missing/diverged — i.e. it
is drift — until an operator resolves it by id: with `--delete-extras` the tool deletes the extras
*before* inserting, so a holder that only exists on the non-authoritative side is removed and the
repair converges in one pass; a slug swap between two ids that exist on both sides is never
repaired automatically.

`article_tags` has **no primary key and no unique index** in either schema, so `INSERT OR IGNORE`
would happily duplicate a pair. For that table the tool inserts with
`insert into article_tags (...) select ?, ? where not exists (select 1 from article_tags where
article_id = ? and tag_id = ?)` instead, and the reconcile merge-join collapses repeated pairs to
one logical row, reporting the surplus copies as `duplicateKeysInMonolith` /
`duplicateKeysInService`. Duplicates are therefore reported but never multiplied and never
repaired away; `tags.name` is likewise not de-duplicated, because `GET /tags` is
`select name from tags` with no `DISTINCT` and duplicate seed names must stay observable.

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
  (77 tests: idempotency, crash-restart, drift detection incl. diverged payload, repair
  convergence, reverse-backfill, empty tables, duplicate pairs, slug conflicts, 10k-row
  performance — for all four domains).
- Comment bodies are free text with newlines and quotes (05 §3 table, row "2 Comment"): a typed
  JDBC copy sidesteps every CSV/escaping problem a shell export would have.
- No Spring Boot: start-up is instant and the jar has two dependencies (sqlite-jdbc, Jackson).

## Build

Requires **JDK 11** (Spotless/google-java-format fails on 17+):

```sh
export JAVA_HOME=/path/to/jdk-11            # e.g. $HOME/jdk-11.0.32.1+1
cd tools/favorite-sync
./gradlew build                              # compiles, runs Spotless check + 77 JUnit tests
./gradlew installDist                        # -> build/install/favorite-sync/bin/favorite-sync
alias favorite-sync="$PWD/build/install/favorite-sync/bin/favorite-sync"
```

`./gradlew spotlessApply` formats the sources.

## Commands

All commands take `--source <monolith db>` and `--target <service db>`, plus the optional
`--domain favorite|comment|tag|article` (default `favorite`). `--key value` and `--key=value` are
both accepted. The examples below use the favorite defaults; for Comment add `--domain comment`
and point `--target` at `comment-service/comment.db`, for Tag and Article add `--domain tag` /
`--domain article` and point `--target` at `article-service/article.db`:

```sh
favorite-sync backfill  --domain comment --source dev.db --target comment-service/comment.db
favorite-sync reconcile --domain comment --source dev.db --target comment-service/comment.db --report out.json
favorite-sync backfill  --domain tag --source dev.db --target article-service/article.db
favorite-sync reconcile --domain tag --source dev.db --target article-service/article.db --report out.json
favorite-sync backfill  --domain article --source dev.db --target article-service/article.db   # after tag
favorite-sync reconcile --domain article --source dev.db --target article-service/article.db --report out.json
```

### `backfill`

```sh
favorite-sync backfill --source dev.db --target favorite.db [--chunk 5000]
favorite-sync backfill --domain comment --source dev.db --target comment-service/comment.db [--chunk 5000]
favorite-sync backfill --domain tag --source dev.db --target article-service/article.db [--chunk 5000]
favorite-sync backfill --domain article --source dev.db --target article-service/article.db [--chunk 5000]
```

1. Records **T0** (wall clock, printed as `backfill T0=...`). Enable
   `extraction.<domain>.write=dual-write` *before* running so every row written after T0 reaches
   the service via the mirror.
2. Takes an online-backup snapshot of `--source` (SQLite `BACKUP TO`, WAL-safe — the live
   `dev.db` is never scanned while the monolith writes to it).
3. Walks the snapshot in keyset order on the natural key (`(article_id, user_id)` for favorite,
   `id` for comment and article, `id` then `(article_id, tag_id)` for tag), `--chunk` rows at a time
   (default 5000), and applies each chunk to `--target` as one `INSERT OR IGNORE` transaction
   (an `insert ... where not exists` transaction for `article_tags`). All columns of the domain table are copied;
   a row whose key already exists in the target is left untouched (never overwritten — use
   `reconcile --repair` for that).

Idempotent and restartable: a crash mid-chunk rolls that chunk back; re-running skips everything
already present. Output:

```
backfill domain=favorite table=article_favorites source=dev.db target=favorite.db chunk=5000
backfill T0=2026-09-03T04:00:00.123Z
backfill snapshot=/tmp/favorite-sync-snapshot-123.db
backfill sourceRows=128431 targetRowsBefore=0
backfill chunk=1 rows=5000 inserted=5000 skipped=0 lastKey=article-0421|user-17
...
backfill done rowsRead=128431 rowsInserted=128431 rowsSkipped=0 chunks=26 targetRowsAfter=128431 T0=2026-09-03T04:00:00.123Z table=article_favorites
```

`--domain tag` prints that block twice, `table=tags` then `table=article_tags`, followed by a
run total:

```
backfill done domain=tag tables=2 rowsRead=1512 rowsInserted=1512 rowsSkipped=0 chunks=2 T0=2026-09-03T04:00:00.123Z
```

`--domain article` additionally prints one `backfill conflict table=articles [id=...] slug=<slug>
held by <other id>` line per slug clash and a `conflicts=N` counter in the `done` line. Such rows
are **skipped, not written**; resolve them by id (see `reconcile`) and re-run.

Exit code 0 on success, **1 if any slug conflict was reported** (the rows are still missing), 2
on error.

### `reverse-backfill`

```sh
favorite-sync reverse-backfill --source dev.db --target favorite.db [--chunk 5000]
favorite-sync reverse-backfill --domain comment --source dev.db --target comment-service/comment.db [--chunk 5000]
favorite-sync reverse-backfill --domain tag --source dev.db --target article-service/article.db [--chunk 5000]
```

Exactly `backfill` with source and target swapped: copies `favorite.db -> dev.db` (or
`comment.db -> dev.db`, `article.db -> dev.db`). Used when rolling back from state C (service
authoritative) — rows that only exist in the service are inserted into the monolith by natural
key; rows already in the monolith are untouched (`INSERT OR IGNORE`). For Comment and Article this
is the "reverse backfill by `id`" of phase-2-comment.md / phase-4-article.md §6; rows *deleted* in
state C are not removed from the monolith by this command — follow it with
`reconcile --authoritative service` (and, if you accept the service's view,
`--repair to-source --delete-extras`). For Article, a slug that a post-cutover service row and a
stale monolith row hold under different ids is reported as a conflict on the monolith side
(`uniqueConflictsInMonolith`) and the service row is skipped until the monolith row is deleted
(`--repair to-source --delete-extras`) or fixed by hand.

### `reconcile`

```sh
favorite-sync reconcile --source dev.db --target favorite.db \
    [--report out.json] [--repair none|to-target|to-source] [--delete-extras] \
    [--authoritative monolith|service] [--max-repair N]
```

Streams both tables in natural-key order and merge-joins them (memory bounded by the drift, not
by the table size). Buckets:

- `missingInService` — key in `--source` but not in `--target`
- `extraInService` — key in `--target` but not in `--source`
- `diverged` — key on both sides but the payload differs. Always empty for `favorite`
  (`article_favorites` has no mutable payload). For `comment` every column
  (`body, article_id, user_id, created_at, updated_at`) is compared value-for-value including the
  SQLite storage class, and each entry lists the differing `columns`, e.g.
  `{"id":"<uuid>","columns":["body"]}`. `divergedTotal` is added for domains with a payload.
  For `tag` only `tags.name` can diverge; `article_tags` is key-only. For `article` all seven
  payload columns (`user_id, slug, title, description, body, created_at, updated_at`) are compared
  the same way as for `comment`.
- `duplicateKeysInMonolith` / `duplicateKeysInService` — only for `article_tags`: pairs stored
  more than once on that side. They are **not** counted as drift (the data is present on both
  sides) and no repair removes them.
- `uniqueConflictsInService` / `uniqueConflictsInMonolith` — only for `articles`: a
  `missingInService`/`diverged` row whose `slug` is held by a different `id` in the service
  (resp. an `extraInService`/`diverged` row whose slug is held by a different id in the monolith).
  Diagnostic — the row is already counted once as missing/extra/diverged; the conflict entry tells
  you *why* a repair cannot write it and which id is in the way.

Note the report's `monolithCount`/`serviceCount`/`missingInService`/`extraInService` are always
named from the point of view *source = monolith, target = service*, regardless of `--repair` or
`--authoritative`. **Do not swap `--source`/`--target` for reconcile** — use `--repair to-source`
and `--authoritative service` instead.

| Option | Effect |
|---|---|
| (none) | Report-only. Exit **1** if drift > 0, else 0 — suitable for cron/CI gating. |
| `--report out.json` | Also write the 05 §4.2 JSON document (directories are created). |
| `--repair to-target` | `INSERT OR IGNORE` every `missingInService` row into the service DB and `UPDATE` every `diverged` row from the monolith's values (monolith authoritative, states A/B). |
| `--repair to-source` | `INSERT OR IGNORE` every `extraInService` row into the monolith DB and `UPDATE` every `diverged` row from the service's values (service authoritative, rollback from state C). |
| `--delete-extras` | Only with `--repair`: additionally delete rows that the authoritative side does not have (`extraInService` for `to-target`, `missingInService` for `to-source`). **Never deletes without this flag.** |
| `--authoritative` | Label written to the report (`"authoritative"` field). Default `monolith`. |
| `--max-repair N` | Abort (exit 2, nothing written) if a repair would touch more than N rows (inserts + updates + deletes). Default `max(1000, 1 % of authoritative rows)` per 05 §4.3; `0` disables the guard. |

After a repair the diff is recomputed; the exit code is 0 only when the *remaining* drift is zero.

For `articles` a repair applies deletes (`--delete-extras`) **first**, then inserts and updates,
and skips every insert/update whose slug is still held by another id after the deletes (logged as
`reconcile table=articles skipped=N rows whose unique value is held by another key ...`). Skipped rows remain in the `after` diff, so the exit code
is 1 and the report shows what is left to resolve by hand.

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
  "repair" : { "mode" : "to-target", "deleteExtras" : false, "inserted" : 1, "deleted" : 0, "updated" : 0,
               "monolithCountAfter" : 128431, "serviceCountAfter" : 128430,
               "missingInServiceAfter" : 0, "extraInServiceAfter" : 0, "divergedAfter" : 0 }
}
```

With `--domain comment` the same document is produced with `"domain" : "comment"`,
`"table" : "comments"`, key objects of the form `{ "id" : "<uuid>" }` and a populated
`diverged` bucket:

```json
  "tables" : [ {
    "table" : "comments",
    "missingInService" : [ { "id" : "7f1c..." } ], "missingInServiceTotal" : 1,
    "extraInService" : [ ], "extraInServiceTotal" : 0,
    "diverged" : [ { "id" : "a9e2...", "columns" : [ "body" ] } ], "divergedTotal" : 1,
    "status" : "DRIFT"
  } ]
```

With `--domain tag` the document has `"domain" : "tag"` and **two** entries in `tables`, always in
this order; `summary.drift`/`summary.clean` count tables and `summary.driftRows` sums both:

```json
  "tables" : [ {
    "table" : "tags",
    "monolithCount" : 812, "serviceCount" : 812,
    "missingInService" : [ ], "missingInServiceTotal" : 0,
    "extraInService" : [ ], "extraInServiceTotal" : 0,
    "diverged" : [ { "id" : "3f0c...", "columns" : [ "name" ] } ], "divergedTotal" : 1,
    "status" : "DRIFT"
  }, {
    "table" : "article_tags",
    "monolithCount" : 3907, "serviceCount" : 3906,
    "missingInService" : [ { "articleId" : "article-3", "tagId" : "3f0c..." } ],
    "missingInServiceTotal" : 1,
    "extraInService" : [ ], "extraInServiceTotal" : 0,
    "diverged" : [ ],
    "duplicateKeysInMonolith" : [ { "articleId" : "article-9", "tagId" : "a1b2..." } ],
    "duplicateKeysInMonolithTotal" : 1,
    "duplicateKeysInService" : [ ], "duplicateKeysInServiceTotal" : 0,
    "status" : "DRIFT"
  } ],
  "summary" : { "drift" : 2, "clean" : 0, "driftRows" : 2, "status" : "DRIFT" }
```

With `--domain article` the document has `"domain" : "article"`, `"table" : "articles"`, key
objects `{ "id" : "<uuid>" }`, the `diverged` bucket of the comment shape and two extra buckets:

```json
  "tables" : [ {
    "table" : "articles",
    "monolithCount" : 1207, "serviceCount" : 1207,
    "missingInService" : [ { "id" : "7f1c..." } ], "missingInServiceTotal" : 1,
    "extraInService" : [ { "id" : "c0de..." } ], "extraInServiceTotal" : 1,
    "diverged" : [ { "id" : "a9e2...", "columns" : [ "slug", "title" ] } ], "divergedTotal" : 1,
    "uniqueConflictsInService" : [ { "id" : "7f1c...", "column" : "slug", "value" : "how-to-train-your-dragon", "conflictingId" : "c0de..." } ],
    "uniqueConflictsInServiceTotal" : 1,
    "uniqueConflictsInMonolith" : [ { "id" : "c0de...", "column" : "slug", "value" : "how-to-train-your-dragon", "conflictingId" : "7f1c..." } ],
    "uniqueConflictsInMonolithTotal" : 1,
    "status" : "DRIFT"
  } ],
  "summary" : { "drift" : 1, "clean" : 0, "driftRows" : 3, "status" : "DRIFT" }
```

`--max-repair` is evaluated over the **whole run** (both tables) before anything is written, so a
mass-drift tag run leaves both tables untouched. The `repair` block's `*After` counters are sums
over the tables.

Key lists are truncated at 1000 entries per bucket; `<bucket>Total` always holds the full count and
`"truncated": true` is set on the table when truncation happened. Repair always acts on the full
set, not on the truncated list. Two identical tables always produce identical checksums.

One-line summaries are printed for logs/alerts:

```
reconcile domain=favorite table=article_favorites phase=before monolith=128431 service=128429 missing=2 extra=0 diverged=0 status=DRIFT
reconcile domain=comment table=comments phase=before monolith=4213 service=4212 missing=1 extra=0 diverged=1 status=DRIFT
reconcile domain=tag table=tags phase=before monolith=812 service=812 missing=0 extra=0 diverged=1 status=DRIFT
reconcile domain=tag table=article_tags phase=before monolith=3907 service=3906 missing=1 extra=0 diverged=0 duplicateKeys=2/0 status=DRIFT
reconcile domain=article table=articles phase=before monolith=1207 service=1207 missing=1 extra=1 diverged=1 conflicts=1/1 status=DRIFT
```

`graceSeconds` is always `0`. `article_favorites` has no timestamp column, and for `comments` the
`created_at` column is stored in two different forms (INTEGER millis by the application, TEXT by
the seed script), so the 60 s in-flight grace of 05 §4.1 is deliberately not applied per row. Run
reconcile against a quiet system, or treat a single transient `DRIFT` followed by `CLEAN` as
in-flight dual-write, not as a defect.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | success; for `reconcile`, zero remaining drift |
| 1 | `reconcile`: drift remains (report-only, or after a repair that was not allowed to delete or was blocked by a slug conflict); `backfill --domain article`: slug conflicts were skipped |
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
`CommentSyncTest` (17, `--domain comment`): backfill copies all columns with exact timestamp
storage class (INTEGER and TEXT) and is idempotent, crash after chunk 2 then restart converges,
existing target rows never overwritten, empty tables, missing `comments` table, 10k rows;
reconcile detects missing/extra/diverged body and diverged timestamps with report shape,
identical/empty tables clean, repair to-target (insert + overwrite diverged, extras kept) with and
without `--delete-extras`, repair to-source both ways, never deletes without the flag,
`--max-repair` counts diverged rows, 10k rows; CLI backfill -> reconcile clean -> state C writes ->
`reverse-backfill` -> `reconcile --authoritative service`, unknown `--domain` rejected and the
default stays `favorite`.

`TagSyncTest` (15, `--domain tag`): backfill copies `tags` before `article_tags` and is
idempotent, crash after a chunk then restart converges across the table boundary, existing target
rows never overwritten, empty tables, missing `article_tags` table, duplicate source pairs stored
once and not multiplied on re-run, 10k rows (5k tags + 5k pairs) backfilled and reconciled;
reconcile reports drift per table with the two-entry report, diverged `tags.name`, duplicate pairs
reported without being drift, identical/empty tables clean, repair to-target (with and without
`--delete-extras`) and to-source converge both tables, `--max-repair` counts both tables and
writes nothing; CLI backfill -> reconcile -> state C writes -> `reverse-backfill` ->
`reconcile --authoritative service`.

`ArticleSyncTest` (21, `--domain article`): domain covers only `articles`; backfill copies all
seven columns with exact timestamp storage class (INTEGER and TEXT) and is idempotent, leaves
`tags`/`article_tags` untouched, crash after chunk 3 then restart converges, existing target rows
never overwritten, empty table, slug clash reported as a conflict (row skipped, others inserted,
CLI exit 1 until resolved); `reverse-backfill` copies service rows back without touching tag
tables; reconcile detects missing/extra and a divergence in **each** of the seven columns with the
report shape, timestamps compare as stored (INTEGER vs TEXT of the same instant is drift), empty
tables clean, repair to-target with and without `--delete-extras` (extras' `article_tags` left in
place), repair to-source, `--max-repair` writes nothing; slug held by another id is reported on
both sides, a repair without `--delete-extras` skips it and exits 1, with `--delete-extras` the
holder goes first and the repair converges, a slug *swap* between two ids is blocked, a slug moving
to a free value is repaired; CLI backfill -> reconcile (report) -> `--repair to-target
--delete-extras --max-repair` -> clean, `--help` mentions article; 10k rows backfilled, reconciled
and repaired.

The Phase 1 suites (24 tests), the Phase 2 suite (17 tests) and the Phase 3 suite (15 tests) run
unchanged as the favorite + comment + tag regression — 56 of the 77 tests. Only one assertion
changed: the Phase 2 CLI test used `--domain article` as its example of an *unknown* domain and
now uses `--domain user`.

## Design notes / open questions

- **Direct DB writes for repair.** 05 §4.3 prefers repairing through the service's internal API so
  there is a single write path. This tool implements repair as direct `INSERT OR IGNORE` /
  `UPDATE` / `DELETE` on the SQLite file (as specified for the tooling) — acceptable for tables
  with no service-side validation beyond "body not blank", but the service must be stopped or the
  file must not be under concurrent writes while `--repair` runs (SQLite locking makes concurrent
  writes safe but not *coordinated*). Revisit if a service gains validation logic.
- **Comment `diverged` semantics.** A comment has no update endpoint in Conduit, so a `diverged`
  row is a dual-write bug or a manual edit, never normal traffic; repair overwrites the whole
  payload from the authoritative side. Because the comparison includes the SQLite storage class,
  a service that re-wrote `created_at` as TEXT while the monolith stored INTEGER millis would be
  flagged — that is intentional (the monolith's `DateTimeHandler` reads the two forms
  differently), and is why the tool copies raw values instead of reformatting them.
- **Tag duplicates are data, not drift.** `article_tags` has no unique constraint and
  `MyBatisArticleRepository.createNew` can write the same pair twice (two articles created with
  the same tag name race on `findTag`), while `tags.name` is not unique either. The tool copies
  one row per distinct pair, reports the surplus and never deletes it: removing it would change
  `GET /tags` (no `DISTINCT`) and the `ArticleData.tagList` cardinality. Deciding whether the
  monolith should de-duplicate is a Phase 3/4 product question, not a sync-tool one.
- **Tag rows are never garbage-collected.** Article update/delete does not touch `tags` or
  `article_tags`, so a tag with no articles is normal, expected state on both sides.
- **Orphans are not drift.** `comments.article_id` has no FK; comments of deleted articles exist
  on both sides and compare equal. Reconcile never "cleans up" orphans (05 §7.2). The same holds
  for `article_tags`/`article_favorites`/`comments` rows of an article that `--repair
  --delete-extras` removes from `articles`: the monolith's `DELETE /articles/{slug}` does not
  cascade either, so the tool does not.
- **Article slug conflicts are resolved by id, by hand.** phase-4-article.md §6: "reconcile
  conflicts by id first". The tool reports which id holds the slug and refuses to guess whether the
  monolith's or the service's article is the real one (the only automatic path is
  `--delete-extras`, which removes a holder the authoritative side does not have at all). A slug
  swap between two ids that exist on both sides is therefore never auto-repaired.
- **No `--domain article-bundle`.** Running `tag` and `article` in one process would need either a
  multi-table domain with a *different* unique-conflict policy per table or a wrapper that runs two
  `Main.run` calls and merges two JSON reports/exit codes; neither is trivial, and two commands
  keep the per-domain reports and exit codes exactly as documented. Open question for the
  orchestrator: add a bundle if operators find two invocations error-prone.
- **Standalone job reads both files** (05 §9 Q7). The backfill only reads a `.backup` snapshot of
  `dev.db`; `reconcile` reads both live files read-only unless repairing. If "no shared SQLite"
  is enforced strictly, run reconcile against `.backup` copies of both files.
- `--authoritative` is a report label only; it does not change which side is repaired. This is
  deliberate so that a typo can never silently flip the repair direction.
