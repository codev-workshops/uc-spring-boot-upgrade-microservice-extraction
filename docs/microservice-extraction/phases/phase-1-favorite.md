# Phase 1 — Favorite extraction

Status: **design, awaiting approval**. No implementation starts before explicit approval.

## 1. Scope

Extract the Favorite domain into `favorite-service` (proposed port 8081, DB file `favorite.db`).

Owned data: table `article_favorites (article_id, user_id, PK(article_id, user_id))`.
Seed data: the `article_favorites` rows in `V2__seed_data.sql`.

### Monolith touch points (exhaustive)

| Layer | Class / file | Usage |
|-------|--------------|-------|
| api | `io.spring.api.ArticleFavoriteApi` | `POST/DELETE /articles/{slug}/favorite` -> `ArticleFavoriteRepository.save/find/remove`, then `ArticleQueryService.findBySlug` |
| graphql | `io.spring.graphql.ArticleMutation` (`favoriteArticle`, `unfavoriteArticle`) | same write path as the REST API |
| application | `io.spring.application.ArticleQueryService.setFavoriteCount` / `setIsFavorite` | reads via `ArticleFavoritesReadService.articlesFavoriteCount(ids)` / `userFavorites(ids, user)` |
| application | `ArticleQueryService.findRecentArticles/findRecentArticlesWithCursor(..., favoritedBy, ...)` | `favoritedBy` filter is a SQL join in `ArticleReadService.xml` (`left join article_favorites AF ... AFU.username = #{favoritedBy}`) |
| infrastructure | `MyBatisArticleFavoriteRepository`, `ArticleFavoriteMapper(.xml)`, `ArticleFavoritesReadService(.xml)` | persistence |
| core | `io.spring.core.favorite.ArticleFavorite`, `ArticleFavoriteRepository` | domain |
| resources | `mapper/ArticleFavoritesReadService.xml`, `mapper/ArticleFavoriteMapper.xml`, `mapper/TransferData.xml#favoriteCount`, `ArticleReadService.xml#selectArticleIds` | SQL |

Note: `articlesFavoriteCount` and `userFavorites` currently `left join articles` — the service will
own only `article_favorites`, so these queries are rewritten to operate on `article_favorites` alone
(`select article_id, count(*) ... where article_id in (...) group by article_id`). Behaviour for
IDs with zero favorites is preserved at the caller (see contract 4.3).

## 2. Planned changes (exact)

### 2.1 New service `favorite-service/` (from `templates/03-extracted-service-template.md`)
- `build.gradle` (Spring Boot 2.6.3, same deps as monolith), `settings.gradle`, own wrapper.
- `src/main/resources/db/migration/V1__create_favorite_tables.sql` — `article_favorites` only;
  `V2__seed_favorites.sql` — favorite rows from monolith seed.
- Packages `io.spring.favorite.{api,core,application,infrastructure}`.
- Internal REST API (all responses wrapped, same error format as monolith):
  - `GET  /internal/favorites/counts?articleIds=a,b,c` -> `{"counts":[{"articleId":"a","count":2}, ...]}` (only IDs with count>0, mirroring the current left-join semantics)
  - `GET  /internal/favorites/users/{userId}?articleIds=...` -> `{"articleIds":[...]}`
  - `GET  /internal/favorites/by-user/{userId}/article-ids` -> article IDs favorited by a user (for `favoritedBy`)
  - `PUT  /internal/favorites/{articleId}/{userId}` (idempotent insert; 200 if exists) and `DELETE` (idempotent; 204)
- JWT filter reusing the monolith `jwt.secret` so forwarded `Token <jwt>` headers validate.

### 2.2 Monolith seam (per `04-strangler-wiring-design.md`)
- `ExtractionProperties` (`extraction.favorite.enabled=false`, `extraction.favorite.dual-write=false`, `extraction.favorite.base-url`).
- Introduce interface `FavoriteQueryPort` with:
  - `MyBatisFavoriteQueryAdapter` (wraps existing `ArticleFavoritesReadService`, default)
  - `RemoteFavoriteQueryAdapter` -> `FavoriteServiceClient` (RestTemplate, 500 ms timeout) + DTOs `FavoriteCountDto`, `UserFavoritesDto` in `io.spring.application.favorite.dto`.
  - `RoutingFavoriteQueryPort` selects by flag at call time (flag flip needs no restart when backed by `@RefreshScope`/env re-read; at minimum a restart-free properties reload is not required for rollback — see §6).
- `ArticleQueryService.setFavoriteCount/setIsFavorite` depend on `FavoriteQueryPort` instead of `ArticleFavoritesReadService` (constructor injection; pseudo-diff below).
- `favoritedBy` filter: when flag ON, resolve `favoritedBy -> userId -> articleIds` via the client and pass `ids` into a new `ArticleReadService.queryArticlesByIds` variant; when OFF keep the SQL join.
- Write path (`ArticleFavoriteApi`, `ArticleMutation`): introduce `FavoriteCommandPort` with `LocalFavoriteCommand` (existing repo), `RemoteFavoriteCommand` (client), and `DualWriteFavoriteCommand` (local first, then remote; remote failure logged + queued for reconciliation, never surfaced to the user).

```java
// ArticleQueryService (pseudo-diff)
- private ArticleFavoritesReadService articleFavoritesReadService;
+ private FavoriteQueryPort favoriteQueryPort;
  private void setFavoriteCount(List<ArticleData> articles) {
-   List<ArticleFavoriteCount> counts = articleFavoritesReadService.articlesFavoriteCount(ids);
+   List<ArticleFavoriteCount> counts = favoriteQueryPort.articlesFavoriteCount(ids);
    ... // unchanged: countMap.get(id) may be null -> preserved
  }
```

### 2.3 Not changed in Phase 1
Domain classes `ArticleFavorite`/`ArticleFavoriteRepository` and the MyBatis mapper stay in the
monolith until cutover; they back the flag-OFF path and the authoritative store.

## 3. Risks

| Risk | Mitigation |
|------|-----------|
| `favoritedBy` filter changes from one SQL query to id-list resolution; pagination/ordering must stay identical | parallel-run harness compares `GET /articles?favoritedBy=` envelopes (offset and cursor variants) |
| `favoritesCount` null-vs-0 serialization contract in `setFavoriteCount` | golden edge-case test pins the exact JSON; remote adapter reproduces the same map semantics |
| Service unavailable -> article listings degrade | client failure falls back to the local MyBatis adapter while dual-write keeps the local table authoritative (fallback is only removed at cutover) |
| Double-favorite: PK violation on `insert` | service uses idempotent `insert or ignore`; monolith behaviour captured by Phase 0 test and preserved on the flag-OFF path |
| SQLite `IN (...)` variable limit for large id batches | batch size capped at 500 in both adapters (Phase 0 test) |
| N+1 / latency: each article page now needs 2 remote calls | both calls are batched per page (they already are), timeouts + metrics tagged `route=extracted` |
| JWT forwarding | same secret; contract test for `Token` header acceptance |

## 4. Validation

1. **Golden tests** — all 27 baseline files (`00-golden-test-baseline.md`) pass with flag OFF and with flag ON (`ArticleFavoriteApiTest`, `ArticlesApiTest`, `ListArticleApiTest`, `ArticleQueryServiceTest`, `MyBatisArticleFavoriteRepositoryTest` are the critical ones).
2. **Contract tests** (`01-contract-testing.md`) — consumer (monolith) contracts for the four internal endpoints above; provider verification runs in `favorite-service`.
3. **Edge cases** (Phase 0 Favorite tests): idempotent double-favorite, unfavorite-when-not-favorited, favorite non-existent slug -> 404, null-vs-0 count, empty/large id batches, anonymous reads, `favoritedBy` filter.
4. **Parallel run** (`02-parallel-run-harness.md`) — `FavoriteParallelRunTest` MONOLITH vs EXTRACTED for `GET /articles/{slug}`, `GET /articles?favoritedBy=`, `POST/DELETE /articles/{slug}/favorite`, GraphQL `favoriteArticle`.
5. **Reconciliation** — zero drift between `article_favorites` in `dev.db` and `favorite.db` for N consecutive runs before cutover.
6. Both `./gradlew build -x jacocoTestCoverageVerification` (monolith and service) green.

## 5. Step-by-step execution order

1. Create `favorite-service` from template; V1 migration; internal endpoints; provider contract tests. (No monolith change yet.)
2. Backfill `article_favorites` from `dev.db` -> `favorite.db`; run reconciliation report.
3. Monolith: add `ExtractionProperties`, ports, adapters, client (flag OFF). Golden tests green.
4. Enable `dual-write=true` (writes go local then remote). Reconcile until zero drift.
5. Enable `enabled=true` for reads (shadow/parallel-run first, then live). Monitor.
6. Cutover: service becomes authoritative for writes; monolith local write becomes the mirror.
7. Decommission (later, separate approval): remove MyBatis favorite mapper + table from monolith.

## 6. Rollback (any step, no data loss)

1. Flip `extraction.favorite.enabled=false` -> all reads/writes go to the monolith table, which has been continuously written (local-first dual-write). No restart is required if properties are externalized/refreshable; otherwise a restart with the env var unset.
2. Leave `dual-write` on (harmless) or turn off to stop remote calls.
3. If rollback happens after step 6 (service was authoritative), run reverse backfill `favorite.db -> dev.db` for rows written during the window (natural key makes this an idempotent upsert), then reconcile.
4. Because there are no FK constraints, the monolith table can be truncated/re-filled freely without affecting `articles`/`users`.
