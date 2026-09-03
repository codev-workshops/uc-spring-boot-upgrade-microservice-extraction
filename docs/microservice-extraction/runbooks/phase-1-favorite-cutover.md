# Runbook — Phase 1 Favorite cutover and rollback

Operator procedure for moving `article_favorites` from the monolith (`dev.db`) to
`favorite-service` (`favorite.db`). It follows
[`phases/phase-1-favorite.md`](../phases/phase-1-favorite.md) §5/§6 and
[`05-data-sync-and-rollback-design.md`](../05-data-sync-and-rollback-design.md) §3–§6, using the
flag names of [`04-strangler-wiring-design.md`](../04-strangler-wiring-design.md) §1.1 and the
`favorite-sync` CLI in [`tools/favorite-sync`](../../../tools/favorite-sync/README.md).

## 0. Prerequisites

| Item | Check |
|---|---|
| JDK 11 | `java -version` → 11.x (`export JAVA_HOME=...`) |
| CLI built | `cd tools/favorite-sync && ./gradlew installDist` → `build/install/favorite-sync/bin/favorite-sync`; below aliased as `favorite-sync` |
| Service schema | `favorite-service` has been started at least once against `favorite.db` so its Flyway `V1__create_favorite_tables.sql` created `article_favorites` (the CLI refuses to run otherwise: `table 'article_favorites' is missing in favorite.db`) |
| Service healthy | `curl -s http://localhost:8081/actuator/health` → `{"status":"UP"}` |
| Shared JWT secret | monolith and service use the same `JWT_SECRET` |
| Baseline | monolith `./gradlew build -x jacocoTestCoverageVerification` green, all flags at defaults (`extraction.favorite.enabled=false`) |
| Reports dir | `mkdir -p build/reconcile/favorite` |

Flags are set as env vars (Spring relaxed binding) and take effect on (rolling) restart, e.g.
`EXTRACTION_FAVORITE_ENABLED=true EXTRACTION_FAVORITE_WRITE=dual-write`. If the optional runtime
flip endpoint (04 §1.3 b) is deployed, the same key/value pairs are POSTed instead.

State reference (05 §5):

| State | `extraction.favorite.enabled` | `.write` | `.read` | Authority |
|---|---|---|---|---|
| Off (today) | `false` | – | – | monolith |
| A. Shadow-write | `true` | `dual-write` | `monolith` | monolith |
| A'. Shadow-read | `true` | `dual-write` | `shadow` | monolith |
| B. Parallel read | `true` | `dual-write` | `extracted` | monolith |
| C. Service authoritative | `true` | `extracted` | `extracted` | **service** |

## 1. Enter state A — enable dual-write

```sh
EXTRACTION_FAVORITE_ENABLED=true \
EXTRACTION_FAVORITE_WRITE=dual-write \
EXTRACTION_FAVORITE_READ=monolith \
EXTRACTION_FAVORITE_BASE_URL=http://localhost:8081 \
EXTRACTION_FAVORITE_FALLBACK=monolith \
  <restart monolith>
```

Verify: favorite an article through the public API and confirm the row appears in **both** files.

```sh
curl -s -X POST -H "Authorization: Token $JWT" localhost:8080/articles/<slug>/favorite | jq .article.favorited   # true
```

Expected: `200`, and the `PUT /internal/favorites/{articleId}/{userId}` call is visible in the
service log. Mirror failures must appear only as WARN + outbox rows; the user request stays 2xx.

## 2. Record T0 and backfill (05 §3)

Dual-write must already be on (step 1) so rows written after T0 arrive via the mirror.

```sh
favorite-sync backfill --source dev.db --target favorite.db --chunk 5000 | tee build/reconcile/favorite/backfill.log
```

Expected output (counts will differ):

```
backfill T0=2026-09-03T04:00:00.123Z
backfill snapshot=/tmp/favorite-sync-snapshot-....db
backfill sourceRows=128431 targetRowsBefore=0
backfill chunk=1 rows=5000 inserted=5000 skipped=0 lastKey=...
...
backfill done rowsRead=128431 rowsInserted=128431 rowsSkipped=0 chunks=26 targetRowsAfter=128431 T0=2026-09-03T04:00:00.123Z
```

Record **T0** in the change ticket. `rowsSkipped > 0` is normal when dual-write already mirrored
rows or the job is re-run after an interruption — simply re-run the same command until it
finishes; it is idempotent. Exit code must be `0`.

## 3. Reconcile to zero drift, N times (05 §4, §5.1)

Report-only run immediately after the backfill:

```sh
favorite-sync reconcile --source dev.db --target favorite.db \
  --report build/reconcile/favorite/$(date -u +%Y%m%dT%H%M%SZ).json; echo exit=$?
```

Expected:

```
reconcile domain=favorite table=article_favorites phase=before monolith=128431 service=128431 missing=0 extra=0 diverged=0 status=CLEAN
reconcile report=build/reconcile/favorite/20260903T040500Z.json
exit=0
```

- `exit=1` with `missing>0`: rows written after the snapshot whose mirror failed, or a mirror
  bug. Wait for the outbox to drain and re-run. If it persists, repair toward the monolith:
  ```sh
  favorite-sync reconcile --source dev.db --target favorite.db --repair to-target \
    --report build/reconcile/favorite/repair-$(date -u +%Y%m%dT%H%M%SZ).json
  ```
  Expected: `reconcile repair=to-target inserted=<n> deleted=0 ...`, then a `phase=after ... status=CLEAN` line, `exit=0`.
- `extra>0`: unfavorites whose DELETE mirror failed (or rows only the service has). Investigate
  the keys listed in the report; if the monolith is right, add `--delete-extras` to the repair.
  The tool **never deletes** without that flag.
- `exit=2` and `repair would touch N rows, above --max-repair`: mass drift — dual-write is broken.
  Do not override the guard; fix the mirror, then re-run backfill (step 2) and reconcile.

Schedule the report-only command hourly (cron/CI; exit code 1 = alert). The cutover criterion of
05 §5.2 is **N = 7 consecutive days** of `status=CLEAN` (minimum defensible N = 3). Keep every
JSON report — the one-line summaries are the audit trail.

## 4. Enter state A' — `read=shadow`

Entry criteria (05 §5.1 items 1–2): step 3 clean, dual-write running ≥ 24 h with outbox lag ≈ 0.

```sh
EXTRACTION_FAVORITE_READ=shadow <restart monolith>
```

Reads are still served by the monolith; the remote adapter is called asynchronously and diffed
(04 §5). Monitor `extraction.shadow.mismatch` and the WARN diff logs on `GET /articles`,
`GET /articles/{slug}`, `GET /articles?favoritedBy=`, `POST/DELETE /articles/{slug}/favorite`,
and GraphQL `articles`/`article`/`favoriteArticle`/`unfavoriteArticle`.

Exit criterion: ≥ 99.99 % agreement over ≥ 24 h with every mismatch explained (typically an
in-flight dual-write). Keep hourly reconciliation running.

## 5. Enter state B — `read=extracted`

```sh
EXTRACTION_FAVORITE_READ=extracted <restart monolith>   # fallback stays 'monolith'
```

Verify:

```sh
curl -s localhost:8080/articles/<slug> | jq .article.favoritesCount      # same value as before the flip, never null
curl -s "localhost:8080/articles?favoritedBy=<username>" | jq .articlesCount
favorite-sync reconcile --source dev.db --target favorite.db; echo exit=$?   # exit=0
```

Stop the service briefly and confirm article listings still return 200 with counts served by the
fallback (`extraction.favorite.fallback=monolith`). Restart the service.

**Rollback from A/A'/B is a single flag** (05 §6.1): `EXTRACTION_FAVORITE_ENABLED=false` (or
`EXTRACTION_FAVORITE_READ=monolith` to keep dual-write warm). No data work is needed — every write
landed in `dev.db` first.

## 6. Cutover to state C — service authoritative (05 §5.2, §5.3)

Entry criteria: N days of `CLEAN` (step 3), zero dead-lettered outbox rows, the rollback drill
(§8) executed once in a non-production environment, an owner on call.

```sh
favorite-sync reconcile --source dev.db --target favorite.db; echo exit=$?     # must be 0 right before the flip
EXTRACTION_FAVORITE_WRITE=extracted <restart monolith>
date -u +%Y-%m-%dT%H:%M:%SZ   # record T_flip
```

From now on writes go only to the service; `dev.db.article_favorites` is a read-replica kept warm
by the service's reverse mirror (if implemented) — otherwise it starts to fall behind and the
rollback below relies entirely on `reverse-backfill`. Continue hourly reconciliation, now labelled:

```sh
favorite-sync reconcile --source dev.db --target favorite.db --authoritative service \
  --report build/reconcile/favorite/$(date -u +%Y%m%dT%H%M%SZ).json
```

With `--authoritative service` the interesting bucket is `extraInService` (= rows the monolith
replica is missing). To keep the replica warm without a reverse mirror, run periodically:

```sh
favorite-sync reconcile --source dev.db --target favorite.db --authoritative service --repair to-source
```

The monolith table is dropped only after ≥ 30 days without rollback, via a new Flyway migration
(`V3__drop_article_favorites.sql`) — separate approval.

## 7. Rollback from state C (05 §6.2)

1. (Optional for Favorite) pause writes for the flip duration.
2. Flip back to monolith-first:
   ```sh
   EXTRACTION_FAVORITE_WRITE=dual-write EXTRACTION_FAVORITE_READ=monolith <restart monolith>
   ```
   (or `EXTRACTION_FAVORITE_ENABLED=false` to go fully local; `dual-write` keeps a retry cheap).
3. Reverse-backfill the authority-flip window — copies every row that exists only in the service
   into the monolith, leaves monolith rows untouched:
   ```sh
   favorite-sync reverse-backfill --source dev.db --target favorite.db | tee build/reconcile/favorite/reverse-backfill.log
   ```
   Expected:
   ```
   reverse-backfill: copying favorite.db -> dev.db
   backfill sourceRows=128902 targetRowsBefore=128431
   backfill done rowsRead=128902 rowsInserted=471 rowsSkipped=128431 chunks=26 targetRowsAfter=128902 T0=...
   ```
   `rowsInserted` is the size of the window that the reverse mirror had not delivered.
4. Verify: zero missing-in-monolith.
   ```sh
   favorite-sync reconcile --source dev.db --target favorite.db --authoritative service \
     --report build/reconcile/favorite/rollback-$(date -u +%Y%m%dT%H%M%SZ).json; echo exit=$?
   ```
   Expected: `extra=0` (nothing the service has that the monolith lacks). `missing>0` means the
   monolith holds rows the service does not — unfavorites executed in state C whose delete did not
   reach the monolith. Decide per key from the report; to apply the service's view of those
   deletes use `--repair to-source --delete-extras`.
5. Because there are no FK constraints (05 §1.2) nothing else in `dev.db` is affected.

## 8. Rollback drill (05 §6.4) — run once per environment before step 6

Use copies of the databases, never production files.

```sh
cp dev.db drill-dev.db && cp favorite.db drill-favorite.db
```

| # | Action | Command / flag | Assert |
|---|---|---|---|
| 1 | Reach state B | steps 1, 2, 3, 5 above on the drill DBs | `reconcile ... ; echo exit=$?` → `0` |
| 2 | Kill the service mid-traffic; favorite/unfavorite via REST | `kill <favorite-service pid>`; `curl -X POST .../favorite` | every request `200`; rows present in `drill-dev.db` |
| 3 | Restart the service, let the outbox drain | — | `favorite-sync reconcile --source drill-dev.db --target drill-favorite.db` → `status=CLEAN`, `exit=0` (use `--repair to-target` once if the outbox dead-lettered) |
| 4 | Flip to state C and generate writes | `EXTRACTION_FAVORITE_WRITE=extracted`; several favorites/unfavorites | `reconcile --authoritative service` shows `extra>0` (service-only rows) |
| 5 | Execute §7 | `EXTRACTION_FAVORITE_WRITE=dual-write`; `favorite-sync reverse-backfill --source drill-dev.db --target drill-favorite.db` | `rowsInserted` = number of favorites written in step 4 |
| 6 | Verify | `favorite-sync reconcile --source drill-dev.db --target drill-favorite.db --authoritative service; echo exit=$?` | `extra=0` |

Record the six results (with the JSON reports) in the change ticket; this is entry criterion 3 of
05 §5.2.

## 9. Quick reference

| Situation | Command |
|---|---|
| Hourly drift check (alert on exit 1) | `favorite-sync reconcile --source dev.db --target favorite.db --report build/reconcile/favorite/$(date -u +%Y%m%dT%H%M%SZ).json` |
| Service missing rows (states A/B) | `favorite-sync reconcile ... --repair to-target` |
| Service has stale rows the monolith deleted (states A/B) | `favorite-sync reconcile ... --repair to-target --delete-extras` |
| Rebuild service DB from scratch (states A/B) | stop service, delete `favorite.db`, start service (Flyway), `favorite-sync backfill ...`, `favorite-sync reconcile ...` |
| Rollback from A/A'/B | `EXTRACTION_FAVORITE_ENABLED=false` — no data work |
| Rollback from C | `EXTRACTION_FAVORITE_WRITE=dual-write`, then `favorite-sync reverse-backfill ...`, then `favorite-sync reconcile ... --authoritative service` |
| Keep monolith replica warm in state C | `favorite-sync reconcile ... --authoritative service --repair to-source` |
