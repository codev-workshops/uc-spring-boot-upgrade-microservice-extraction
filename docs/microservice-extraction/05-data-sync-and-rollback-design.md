# Data Sync, Reconciliation, Cutover and Rollback Design

Phase 0 design document. **No production code is changed by this document.** It defines how data is
kept consistent between the Conduit monolith and each extracted microservice during the strangler
migration, and how to roll back at any point without data loss.

Migration order (from the program plan): **Favorite → Comment → Tag (bundled with Article) →
Article → User**.

Guiding invariant for every phase up to the explicit authority flip:

> The monolith's SQLite database is the **single source of truth**. The extracted service holds a
> continuously-updated replica. Every user-visible read can be served from the monolith at any
> moment, so rollback is a flag flip.

---

## 1. Schema facts (verified against the repository)

### 1.1 Migrations

Only two Flyway migrations exist:

- `src/main/resources/db/migration/V1__create_tables.sql` — creates `users`, `articles`,
  `article_favorites`, `follows`, `tags`, `article_tags`, `comments`.
- `src/main/resources/db/migration/V2__seed_data.sql` — demo seed data (3 users, 7 tags, 5 articles,
  comments, favorites, follows).

Datasource: `spring.datasource.url=jdbc:sqlite:dev.db` (a single file in the working directory).
The test profile (`src/main/resources/application-test.properties`, note: under `main/resources`)
uses `jdbc:sqlite::memory:` with `spring.flyway.target=1`, i.e. schema only, no seed data.

### 1.2 Confirmed: there are NO foreign-key constraints anywhere

`V1__create_tables.sql` declares only:

| Table | Keys / constraints declared |
| --- | --- |
| `users` | `id` PRIMARY KEY; `username` UNIQUE; `email` UNIQUE |
| `articles` | `id` PRIMARY KEY; `slug` UNIQUE; `created_at`/`updated_at` NOT NULL |
| `article_favorites` | PRIMARY KEY `(article_id, user_id)`; both columns NOT NULL |
| `follows` | none at all (no PK, no unique index) — `user_id`, `follow_id` NOT NULL |
| `tags` | `id` PRIMARY KEY; `name` NOT NULL (**not** unique) |
| `article_tags` | none at all (no PK, no unique index) |
| `comments` | `id` PRIMARY KEY; `created_at`/`updated_at` NOT NULL |

No `REFERENCES`, no `FOREIGN KEY`, no `ON DELETE` clause appears in any migration. All relationships
(`articles.user_id`, `comments.article_id`, `comments.user_id`, `article_favorites.*`,
`article_tags.*`, `follows.*`) are **application-level references only**. Consequences that this
design relies on:

1. A table can be dropped, truncated, re-created or re-pointed at another database **without any
   constraint violation** in the remaining tables. This makes both extraction and rollback cheap.
2. Nothing enforces referential integrity today, so the migration cannot make integrity *worse*;
   it also means the replica must not add FKs (it would reject rows the monolith accepts).
3. Orphan rows are already the monolith's normal behaviour:
   `MyBatisArticleRepository.remove` deletes only from `articles`, leaving the article's rows in
   `article_tags`, `article_favorites` and `comments` behind. Reconciliation must therefore compare
   like-for-like (monolith rows vs replica rows), **not** enforce a stricter invariant.

### 1.3 Table ownership per domain

| Domain (phase) | Tables owned | Natural key used for sync |
| --- | --- | --- |
| Favorite (1) | `article_favorites` | `(article_id, user_id)` — already the PK |
| Comment (2) | `comments` | `id` (UUID) |
| Tag + Article (3–4) | `articles`, `tags`, `article_tags` | `articles.id` (`slug` also UNIQUE); `tags.id` (+`name`); `(article_id, tag_id)` |
| User (5) | `users`, `follows` | `users.id` (+`username`, `email` UNIQUE); `(user_id, follow_id)` |

### 1.4 Cross-table joins in the mappers → future cross-service calls

These joins disappear the moment a table lives in another database. Each one becomes either a
remote call or a local read-replica/denormalised projection.

| Mapper / statement | Join | Becomes after extraction |
| --- | --- | --- |
| `ArticleFavoritesReadService.articlesFavoriteCount`, `.userFavorites` | `articles LEFT JOIN article_favorites` | Favorite service owns the counts; monolith calls it (`GET /internal/favorites/counts?articleIds=…`, `…/is-favorited`) — this is exactly the Phase 1 seam at `ArticleQueryService.setFavoriteCount` / `setIsFavorite` |
| `ArticleReadService.selectArticleData` / `selectArticleIds` / `countArticle` | `articles LEFT JOIN article_tags LEFT JOIN tags LEFT JOIN article_favorites LEFT JOIN users AU LEFT JOIN users AFU` | Hardest seam. Filtering by `favoritedBy` (a *username*) and by `author` needs Favorite + User data. Options: (a) the article/query side keeps a **local replica of the id-only projection** (`article_favorites`, and a `username → id` map) purely for filtering, or (b) resolve `favoritedBy`/`author` to ids via a remote call first, then filter locally by id. This design prefers (b) for correctness and (a) only if latency forces it. |
| `ArticleMapper.selectArticle` | `articles LEFT JOIN article_tags LEFT JOIN tags` | Stays inside one service (Tag is bundled with Article precisely to avoid this becoming remote). |
| `CommentReadService.selectCommentData` | `comments LEFT JOIN users` (reuses `ArticleReadService.profileColumns`) | Comment service stores `user_id` and fetches the author `ProfileData` from the monolith/User service (batch `GET /internal/profiles?ids=…`), with a short-TTL cache and a degraded placeholder profile on failure. |
| `UserRelationshipQueryService.*`, `UserReadService.*` | `follows` / `users` only | Stays inside the User service; the article "feed" path (`findArticlesOfAuthors`) becomes "resolve followed author ids remotely, then query articles locally by id". |
| `TagReadService.all` | `tags` only | Stays with Article/Tag. |

Note that `CommentReadService` textually `<include>`s `ArticleReadService.profileColumns`; the
extracted comment service must inline its own copy of those columns (a mapper-level coupling that
is easy to miss).

---

## 2. Dual-write strategy

### 2.1 Shape

For every phase the pattern is identical:

```
HTTP/GraphQL command  →  monolith command path (existing code)
                             1. write to monolith table   (authoritative, in-transaction)
                             2. after commit: mirror the same change to the extracted service
                                POST/DELETE /internal/<domain>  (idempotent, best-effort)
                             3. on mirror failure: write an outbox/dead-letter row + WARN log
                                → the request still returns 2xx
```

Decisions:

- **Direction: from the monolith's command path, not from the service.** The monolith owns the
  authoritative transaction; making the service the entry point during migration would invert
  authority before we have parity evidence. Writes are mirrored *out* of the monolith.
- **Ordering: monolith first, always.** Never mirror before the local commit — a rolled-back local
  transaction would otherwise leave a phantom row in the replica.
- **Mirror after commit, never inside the transaction.** SQLite serialises writers; holding a
  transaction open across a network call would turn remote latency into lock contention for every
  other writer. Practically this means the mirror call is issued from an after-commit hook
  (`TransactionSynchronization`) or, in the un-transactional paths (e.g. `ArticleFavoriteApi`,
  which has no `@Transactional`), immediately after `repository.save(...)` returns.
- **Never fail the user request because the replica failed.** A mirror failure is an availability
  problem for the *replica*, and the replica is not authoritative. Catch, record, return 2xx.
- **Feature-flagged.** Two independent flags per domain:
  `<domain>.dualwrite.enabled` (mirror writes) and `<domain>.reads.remote` (serve reads from the
  service). Dual-write is switched on first and left running for the whole parallel-run window;
  reads are flipped later and can be flipped back independently.

### 2.2 Idempotency keys

Every mirrored operation must be safely repeatable, because the outbox retries and the backfill can
overlap with live traffic.

| Domain | Idempotency key | Why repeat-safe |
| --- | --- | --- |
| Favorite | `(article_id, user_id)` | Composite PK. `MyBatisArticleFavoriteRepository.save` already does find-then-insert; the replica should use `INSERT … ON CONFLICT DO NOTHING` and delete-by-key. Both favorite and unfavorite are naturally idempotent set operations. |
| Comment | `comments.id` (client-invisible UUID generated by the monolith) | `INSERT … ON CONFLICT(id) DO NOTHING`; delete by `id` is idempotent. |
| Article / Tag | `articles.id`; `tags.id`; `(article_id, tag_id)` | Insert-or-ignore on `id`; **`slug` is UNIQUE**, so an update that changes `title` (and therefore `slug`) must be mirrored as an upsert keyed on `id`, not on `slug`, or a stale replica row will collide. `article_tags` has no unique index in the monolith, so the replica must de-duplicate on `(article_id, tag_id)` rather than blind-inserting. |
| User / Follow | `users.id` (+ UNIQUE `username`, `email`); `(user_id, follow_id)` | Upsert on `id`; `follows` has no PK in the monolith, so the replica de-duplicates on the pair. |

Because every operation is an idempotent upsert/delete keyed on a natural key, **at-least-once
delivery is sufficient** — no exactly-once machinery is needed.

### 2.3 Partial failure matrix

| Case | Result | Handling |
| --- | --- | --- |
| Monolith commit fails | Nothing mirrored (mirror is after-commit) | Normal error path; replica unaffected. |
| Monolith commits, mirror call fails (timeout/5xx/service down) | Monolith correct, replica stale | Outbox row + WARN log with the natural key; retry with exponential backoff; reconciliation (§4) catches anything the retries lose. **User request still succeeds.** |
| Mirror succeeds but the response is lost | Replica correct, monolith thinks it failed | Retry is harmless (idempotent). |
| Two concurrent conflicting mirrors (favorite then unfavorite) | Possible out-of-order apply | Attach a monotonic sequence (outbox row id) per natural key and apply in key order; for set-valued domains (favorite, follow, article_tags) reconciliation converges anyway because the monolith's current state wins. |
| Mirror applied for a row later deleted in the monolith | Replica has an extra row | Reconciliation "extra in replica" → delete during repair. |

Retry/outbox: the simplest form consistent with this codebase is a single local table (in a
**separate** file, e.g. `sync-outbox.db`, so it never pollutes `dev.db`'s schema or the Flyway
history) holding `(seq, domain, operation, natural_key_json, payload_json, attempts, last_error)`.
A `@Scheduled` drainer retries pending rows and drops them once acknowledged. Rows exceeding N
attempts are left for the reconciler and alerted on. Phase 0 does not build this; it is scoped
here so Phase 1 can implement it for `article_favorites` only.

---

## 3. Backfill (one-off copy)

Backfill runs **once per domain**, after the service's Flyway schema is created and *before* reads
are flipped. It may run while the monolith is live, because the dual-write stream plus
reconciliation converge any rows written during the copy.

### 3.1 Procedure

1. **Freeze nothing.** Enable dual-write first, record the wall-clock start `T0`, then start the
   copy. Any row created after `T0` arrives via dual-write; any row the copy also picks up is
   absorbed by the idempotent upsert.
2. **Snapshot the source safely.** SQLite is a single file; do not read `dev.db` under concurrent
   writes with an external tool. Take a consistent copy first:
   `sqlite3 dev.db ".backup /tmp/backfill-source.db"` (online backup API, WAL-safe), then export
   from the copy.
3. **Export per domain, chunked.** Deterministic order + `LIMIT/OFFSET` (or keyset on the PK) with
   a chunk size of 5 000 rows:
   ```sh
   sqlite3 /tmp/backfill-source.db \
     ".mode insert article_favorites" \
     "select article_id, user_id from article_favorites order by article_id, user_id limit 5000 offset 0;" \
     > chunk-0000.sql
   ```
   Import into the service DB with the insert rewritten as `INSERT OR IGNORE` (favorite, tags,
   article_tags, follows) or `INSERT … ON CONFLICT(id) DO UPDATE` (comments, articles, users) so the
   job is restartable and re-runnable.
4. **Alternative: a small Java/CLI job.** For Article/Tag and User the row shapes are wide
   (`body text`, timestamps as Joda `DateTime` via the repo's MyBatis type handlers) and the SQL
   text export is fragile around embedded quotes and NULs. For those phases prefer a one-off
   Spring Boot `CommandLineRunner` in the *extracted service* that opens the monolith DB read-only
   (`jdbc:sqlite:file:dev.db?mode=ro`), pages with keyset pagination and writes through the
   service's own MyBatis mappers, so the exact same type handlers are used on both sides. Favorite
   (two varchar columns) is simple enough for pure SQL.
5. **Verify.** For each table: row count on both sides, plus a checksum over the natural key set:
   ```sql
   -- both sides
   select count(*) from article_favorites;
   select count(*), sum(length(article_id || '|' || user_id)) from article_favorites; -- cheap smoke check
   ```
   The authoritative verification is the reconciliation job of §4 run in report-only mode
   immediately after the backfill; it must report **zero missing** and only "extra in monolith" rows
   that are explained by writes after the snapshot.

### 3.2 Per-domain backfill inventory

| Phase | Tables to copy | Approx. ordering key | Notes |
| --- | --- | --- | --- |
| 1 Favorite | `article_favorites` | `(article_id, user_id)` | Trivial; two columns, no NULLs. |
| 2 Comment | `comments` | `created_at, id` | `body text` may contain newlines/quotes → prefer the Java job. Author profile is *not* copied. |
| 3–4 Tag + Article | `tags`, `article_tags`, `articles` | `tags.id`; `(article_id, tag_id)`; `articles.created_at, id` | Copy `tags` and `article_tags` **before** `articles` is switched to remote reads so tag filtering keeps working; `slug` UNIQUE must be preserved. |
| 5 User | `users`, `follows` | `users.id`; `(user_id, follow_id)` | Copy `password` hashes verbatim (BCrypt strings) — no re-hashing, ever. `follows` may contain duplicate pairs today (no unique index); de-duplicate on copy and record the count of duplicates removed in the report. |

---

## 4. Reconciliation

A scheduled (hourly) and on-demand job that diffs the monolith (authoritative) against the replica
per domain. It is read-only by default; repair is a separate, explicitly-invoked mode.

### 4.1 Algorithm

For each table in the domain:

1. `count(*)` on both sides.
2. Stream the **natural key set** from both sides in the same sorted order and do a merge-join
   diff (memory-bounded, no full table materialisation):
   - key in monolith, absent in replica → `MISSING_IN_SERVICE`
   - key in replica, absent in monolith → `EXTRA_IN_SERVICE`
   - key on both sides → for mutable rows (`comments.body`, `articles.title/slug/description/body/updated_at`, `users.*`) compare a per-row hash → `DIVERGED`
   `article_favorites`, `article_tags`, `follows` have no mutable payload, so key-set equality is
   full equality for them.
3. Ignore rows created within the last `sync.reconcile.grace` (default 60 s) to avoid flagging
   in-flight dual-writes.

### 4.2 Report format

One JSON document per run, plus a one-line summary for logs/alerts:

```json
{
  "runId": "2026-09-03T04:00:00Z",
  "domain": "favorite",
  "authoritative": "monolith",
  "graceSeconds": 60,
  "tables": [
    {
      "table": "article_favorites",
      "monolithCount": 128431,
      "serviceCount": 128429,
      "missingInService": [{"articleId": "article-3", "userId": "user-7"}],
      "extraInService": [],
      "diverged": [],
      "status": "DRIFT"
    }
  ],
  "summary": {"drift": 1, "clean": 0, "status": "DRIFT"}
}
```

```
reconcile domain=favorite table=article_favorites monolith=128431 service=128429 missing=2 extra=0 diverged=0 status=DRIFT
```

Reports are written to `build/reconcile/<domain>/<runId>.json` (or a log sink) and the summary line
is what the "N days of zero drift" cutover criterion is measured from. Key lists are truncated at
1 000 entries per bucket with a `truncated: true` marker.

### 4.3 Repair

Repair is always **toward the authoritative side**:

- While the **monolith** is authoritative:
  - `MISSING_IN_SERVICE` → re-mirror the row (same idempotent upsert as dual-write).
  - `EXTRA_IN_SERVICE` → delete by natural key in the service.
  - `DIVERGED` → overwrite the service row from the monolith.
- After the **authority flip** the same three actions run in the opposite direction against the
  monolith's (now replica) table, so that rollback stays available.
- Repair must run through the *service's internal API*, not by writing another service's database
  file directly; that keeps a single write path and preserves the service's own validation.
- Every repair action is logged with the natural key and the run id so a drift spike can be
  audited. A repair run that would touch more than `sync.repair.maxRows` (default 1 % of rows)
  aborts and alerts instead — mass drift means the dual-write is broken and should be fixed first.

---

## 5. Cutover and authority flip

Three distinct states per domain, each entered by flipping one flag:

| State | `dualwrite.enabled` | `reads.remote` | Authority |
| --- | --- | --- | --- |
| A. Shadow | on | off | Monolith |
| B. Parallel read | on | on | **Monolith** (still written first) |
| C. Service authoritative | reversed (service → monolith) | on | Service |

### 5.1 Entry criteria for state B (reads from the service)

1. Backfill complete and a report-only reconciliation run with zero `MISSING`/`DIVERGED`.
2. Dual-write running for ≥ 24 h with outbox drain lag ≈ 0 and no dead-lettered rows.
3. Shadow-read parity: for the read seam being replaced (Phase 1:
   `ArticleQueryService.setFavoriteCount` / `setIsFavorite`), call both implementations and compare,
   returning the monolith's answer. Require ≥ 99.99 % agreement over ≥ 24 h of production traffic,
   with every mismatch explained.
4. Service p99 latency for the seam within the agreed budget, and a degraded-mode default defined
   (e.g. favorite count falls back to the monolith query, or to `0`/`false`, on service failure).

### 5.2 Entry criteria for state C (authority flip)

1. **≥ 7 consecutive days of zero drift** in hourly reconciliation (the "N days" criterion;
   N = 7 chosen conservatively; the minimum defensible value is 3).
2. No dual-write failures beyond the noise floor for the same window; zero unresolved
   dead-letter rows.
3. Rollback rehearsed at least once in a non-production environment (§6) and the reverse-backfill
   job tested.
4. An owner on call and an agreed maximum flip window (see §6.3).

### 5.3 What changes at the flip

- Write path: the monolith's command path stops writing its own table first. Either the monolith
  calls the service synchronously (and the service mirrors back into the monolith's table), or the
  API route for that domain moves to the service and the service mirrors back. Reverse mirroring is
  kept on deliberately, so the monolith table remains a warm, complete read-replica.
- Read path: reads already come from the service (state B).
- The monolith's table is now a **read-replica / dead table**. It is *not* dropped at flip time; it
  is dropped only after the rollback window closes (≥ 30 days of no rollbacks), by a new Flyway
  migration (`V3__drop_article_favorites.sql`, etc.). Because there are no FK constraints, dropping
  it cannot break any other table or query that has already been migrated off it.
- Reconciliation continues, with `authoritative: "service"`, until the table is dropped.

---

## 6. Rollback

### 6.1 Rolling back from state B (reads remote, monolith still authoritative)

This is the common case and it is trivial:

1. Set `<domain>.reads.remote=false`. Reads immediately return to the monolith's tables and joins.
2. Optionally set `<domain>.dualwrite.enabled=false` to stop mirroring (only if the service is the
   problem; leaving it on keeps the replica warm for a retry).
3. Drain or discard the outbox. Discarding is safe: the replica is not authoritative, and a later
   backfill + reconciliation rebuilds it.
4. **No data restoration is needed at all.** Every write in state B landed in the monolith first,
   in the monolith's own transaction. Nothing was ever written *only* to the service.

### 6.2 Rolling back from state C (service authoritative)

1. Stop accepting writes for the domain for the flip duration (a few seconds; for Favorite this can
   even be skipped and handled by step 4).
2. Set the write path back to monolith-first and `reads.remote=false`.
3. Re-enable monolith → service dual-write (so a second attempt is cheap).
4. **Reverse-backfill the authority-flip window**: run the reconciliation job with
   `authoritative: "service"` scoped to rows created/updated after the flip timestamp, and repair
   *into the monolith*. Because reverse mirroring (§5.3) was kept on, this window is normally
   empty; the job exists to cover the case where the reverse mirror was itself failing.
5. Verify with a report-only reconciliation run: zero `MISSING_IN_MONOLITH`.

### 6.3 Why no data is lost

- **In states A and B**: the monolith commits first, in its own transaction, for every write. The
  service is a strict downstream follower. Losing the service (or its whole database file) loses
  nothing that the monolith does not already hold; the recovery procedure is "backfill again".
- **In state C**: authority moved, but the monolith table keeps receiving every write via reverse
  mirroring, so it is at most `outbox lag` behind. The bounded exposure is exactly the set of writes
  that landed in the service while the reverse mirror was failing — which is precisely what the
  reverse-backfill of §6.2 step 4 repairs, and which reconciliation would have alerted on before
  the operator ever chose to roll back.
- **No FK constraints anywhere** (§1.2) means schema-level rollback is equally safe: dropping,
  truncating and re-creating any of these tables cannot cascade, cannot be blocked by a dependent
  constraint, and cannot leave the database in a state SQLite refuses. Re-adding a table (restoring
  `article_favorites` from a backfill) needs no constraint re-validation, and the remaining tables
  never referenced it at the database level in the first place. The only integrity contract is in
  application code, and application code is what the flag flips back.
- Existing orphan behaviour is unchanged: since `MyBatisArticleRepository.remove` already leaves
  orphan `article_tags`/`comments`/`article_favorites` rows, rollback restores the monolith's
  pre-existing semantics exactly, not a stricter or looser variant.

### 6.4 Rollback drill (run once per phase, in a non-production environment)

1. Copy a representative `dev.db`; enable dual-write; backfill; reach state B.
2. Kill the service mid-traffic → assert user requests still return 2xx and the monolith's data is
   complete.
3. Restart it, drain the outbox, run reconciliation → assert it converges to zero drift.
4. Flip to state C, generate writes, then execute §6.2 → assert zero `MISSING_IN_MONOLITH`.

---

## 7. Per-domain specifics and risks

### 7.1 Favorite (Phase 1) — lowest risk

- Tables: `article_favorites` only. Composite PK `(article_id, user_id)` = idempotency key.
- Write seam: `io.spring.api.ArticleFavoriteApi` (POST/DELETE `/articles/{slug}/favorite`) via
  `ArticleFavoriteRepository`. Note the API path has **no `@Transactional`**, so "mirror after
  commit" here simply means "after `repository.save`/`remove` returns".
- Read seam: `ArticleQueryService.setFavoriteCount` / `setIsFavorite`, backed by
  `ArticleFavoritesReadService` (`isUserFavorite`, `articleFavoriteCount`, `articlesFavoriteCount`,
  `userFavorites`).
- Risks:
  - **Counts are aggregates**, so a single missing row is visible to users as a wrong number, but is
    self-healing on the next repair — no permanent corruption.
  - `articlesFavoriteCount` and `userFavorites` join `articles`; after extraction the service does
    not know which article ids exist. It must return counts only for the ids it is asked about
    (`LEFT JOIN` semantics preserved by defaulting absent ids to `0`), which is what the monolith's
    `group by A.id` currently produces.
  - `ArticleReadService.queryArticles` filters by `favoritedBy` (a *username*): this needs Favorite
    **and** User data. Until Phase 5, resolve the username to a user id in the monolith and ask the
    Favorite service for that user's favorited article ids.
  - Immediate read-after-write: the favorite endpoint returns `articleQueryService.findBySlug(...)`
    right after the write, so the mirrored write must be visible before that read, or the response
    shows a stale count. Mitigation for state B: for this one request, read the count from the
    monolith (still authoritative) or have the service apply the mirror synchronously before
    responding — decided in favour of the former, since the monolith is authoritative anyway.

### 7.2 Comment (Phase 2)

- Table: `comments` (`id` PK; `article_id`, `user_id` are bare references).
- `CommentReadService` joins `users` for the author `ProfileData` and textually includes
  `ArticleReadService.profileColumns` — after extraction the comment service must inline those
  columns and resolve profiles remotely (batch by `user_id`, short-TTL cache, degraded placeholder
  profile on failure so a comment list never 500s).
- Risks: comment bodies are free text (export escaping — prefer the Java backfill job); deleting an
  article leaves orphan comments today, so reconciliation must not "clean up" orphans; author
  profile lookups multiply per-request fan-out (batch them, never per row).

### 7.3 Tag + Article (Phases 3–4)

- Tables: `articles` (`slug` UNIQUE), `tags`, `article_tags` (no PK/unique index).
- Tag is deliberately bundled with Article so the `articles ⋈ article_tags ⋈ tags` joins in
  `ArticleMapper.selectArticle` and `ArticleReadService.selectArticleIds` stay local.
- Risks:
  - **`slug` UNIQUE** is the sharpest edge: a title change rewrites the slug, so mirrored updates
    must be upserts keyed on `articles.id`. A stale replica row holding the old slug plus a new row
    carrying the same slug would raise a UNIQUE violation in the replica but not in the monolith —
    the mirror must therefore apply update-by-id, not insert.
  - `tags.name` is **not** unique, and `ArticleMapper.findTag` looks tags up by `name`; duplicate
    tag names can already exist. The replica must reproduce that, not de-duplicate by name.
  - `article_tags` has no unique index → mirror with an explicit existence check on
    `(article_id, tag_id)`.
  - `ArticleReadService` still needs favorite counts and author usernames → depends on Phases 1 and
    5; sequence the flips so Article reads remotely only after those seams are stable.
  - Cursor pagination keys on `articles.created_at` (`findArticlesWithCursor`); replica timestamps
    must be copied byte-identically or paging will skip/duplicate rows.

### 7.4 User (Phase 5) — highest risk

- Tables: `users` (`username`, `email` UNIQUE), `follows` (no PK at all).
- Everything authenticates through this domain: `JwtService`/`AuthorizationService` and
  `@AuthenticationPrincipal User` on every controller. A user-service outage is a total outage, so
  it must be flipped last, with the monolith retaining a local fallback read path for
  authentication for the whole parallel-run window.
- Risks:
  - **Credentials**: `password` BCrypt hashes are copied verbatim; never re-hash, never log. The
    JWT secret (`jwt.secret`) must be shared identically by monolith and service or all existing
    tokens are invalidated. Token revocation semantics are stateless today (24 h `jwt.sessionTime`),
    so a rollback mid-window must not rotate the secret.
  - UNIQUE `username`/`email` collisions during dual-write: a rename mirrored out of order can
    collide in the replica. Upsert by `id`, and let reconciliation's `DIVERGED` bucket catch the
    rest.
  - `follows` has no PK, so duplicates are possible in the monolith today; the replica de-duplicates
    on `(user_id, follow_id)` and the reconciler reports the duplicate count rather than treating it
    as drift.
  - Feed and profile paths (`UserRelationshipQueryService.followedUsers`, `followingAuthors`) feed
    the article feed → the flip changes latency on the busiest read path.

---

## 8. Validation checklist

Per domain, before flipping any flag:

- [ ] Service Flyway migration creates only that domain's tables, **with no FK constraints added**.
- [ ] Service DB is a separate SQLite file (per AGENTS.md: no shared SQLite database).
- [ ] Dual-write is behind `<domain>.dualwrite.enabled`, defaults to **off**.
- [ ] Remote reads are behind `<domain>.reads.remote`, defaults to **off**.
- [ ] Mirror calls happen after the local commit and can never fail the user request (verified by a
      test that makes the client throw).
- [ ] All mirrored operations are idempotent under replay (test: apply the same payload twice).
- [ ] Outbox drains, retries with backoff, and dead-letters with the natural key in the log.
- [ ] Backfill is restartable and chunked; verification counts recorded in the PR/runbook.
- [ ] Reconciliation runs report-only, emits the §4.2 report, and shows zero drift.
- [ ] Shadow-read parity ≥ 99.99 % over ≥ 24 h with every mismatch explained.
- [ ] Degraded-mode behaviour defined and tested for each remote read (default value or fallback to
      the monolith query).
- [ ] Rollback drill (§6.4) executed and the result recorded.
- [ ] `./gradlew build -x jacocoTestCoverageVerification` green on the monolith and on the service.
- [ ] No existing test file modified or deleted.

## 9. Open questions

1. **Outbox durability**: is a second SQLite file (`sync-outbox.db`) acceptable for the retry queue,
   or should the outbox table live in `dev.db` (which means a new Flyway migration on the monolith
   and therefore a schema change in a "no production change" programme)? *Conservative choice taken
   here: a separate file, so `dev.db`'s schema and Flyway history stay untouched.*
2. **N for the zero-drift window**: this document assumes **7 days**; 3 days is the shortest
   defensible value. Needs a product/ops decision per domain.
3. **Read-after-write on the favorite endpoint**: serve the post-write count from the monolith
   (chosen here, simplest and consistent while the monolith is authoritative) or require a
   synchronous mirror before responding (stronger service parity, worse latency and couples the user
   request to the service's availability)?
4. **`favoritedBy` / `author` filtering in `ArticleReadService`**: remote id resolution per query
   (chosen here) vs. a local id-only replica of `article_favorites` and `username → id` inside the
   Article service. The latter is faster but re-introduces a second copy of favorite data with its
   own drift surface.
5. **Reverse mirroring after the authority flip**: keep it on indefinitely until the monolith table
   is dropped (chosen here — it is what makes §6.2 cheap), or stop it and accept a
   restore-from-backfill rollback?
6. **Duplicate rows already present in `follows` / `article_tags`**: should the migration silently
   de-duplicate (changing observable behaviour, e.g. follower counts) or faithfully replicate the
   duplicates? *Conservative choice taken here: replicate faithfully, report the counts.*
7. **Who runs reconciliation** — the monolith, the extracted service, or a standalone job? A
   standalone job needs read access to both databases, which conflicts with "no shared SQLite";
   read-only access to a `.backup` snapshot is the suggested compromise but needs sign-off.
