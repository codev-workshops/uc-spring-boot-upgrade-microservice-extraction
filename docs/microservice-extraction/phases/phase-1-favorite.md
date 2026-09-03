# Phase 1 — Favorite extraction

Status: **APPROVED (2026-09-03) — implementation in progress**.

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
(`select article_id, count(*) ... where article_id in (...) group by article_id`). Because the
service no longer has the `articles` LEFT JOIN, the remote adapter must itself emit a `count=0` entry
for every requested id (see contract in `00-golden-test-baseline.md`: `favoritesCount` is a primitive
`int`, always `0` for an existing article, and `setFavoriteCount` NPEs when an id has no row).

## 2. Planned changes (exact)

### 2.1 New service `favorite-service/` (from `templates/03-extracted-service-template.md`)
- `build.gradle` (Spring Boot 2.6.3, same deps as monolith), `settings.gradle`, own wrapper.
- `src/main/resources/db/migration/V1__create_favorite_tables.sql` — `article_favorites` only;
  `V2__seed_favorites.sql` — favorite rows from monolith seed.
- Packages `io.spring.favorite.{api,core,application,infrastructure}`.
- Internal REST API — **canonical contract** (supersedes the sketch in `04-strangler-wiring-design.md` §3.1;
  POST bodies are used for id batches to avoid URL-length limits). All responses are JSON; errors use the
  monolith envelope `{"errors":{"body":["..."]}}` (422 invalid body, 401 missing/invalid token, 403 token subject != `userId`):

  | Method / path | Request | Response | Auth |
  |---|---|---|---|
  | `POST /internal/favorites/counts` | `{"articleIds":["a","b"]}` | `200 {"counts":[{"articleId":"a","count":2},{"articleId":"b","count":0}]}` — exactly one entry per requested id, in request order, `0` when none; empty list -> `{"counts":[]}` | none |
  | `POST /internal/favorites/query` | `{"userId":"u","articleIds":["a","b"]}` | `200 {"userId":"u","articleIds":["a"]}` — the favorited subset | none |
  | `GET /internal/favorites/by-user/{userId}/article-ids` | — | `200 {"userId":"u","articleIds":[...]}` (for `favoritedBy`) | none |
  | `PUT /internal/favorites/{articleId}/{userId}` | — | `200 {"articleId":"a","userId":"u","favorited":true}`; idempotent (`insert or ignore`) | `Authorization: Token <jwt>`, JWT subject (= monolith user id) must equal `{userId}` |
  | `DELETE /internal/favorites/{articleId}/{userId}` | — | `204`; idempotent no-op when absent | same as PUT |
  | `GET /actuator/health` | — | `200 {"status":"UP"}` | none |

- JWT filter reusing the monolith `jwt.secret` / `jwt.sessionTime` so forwarded `Token <jwt>` headers validate (subject is the user id, see `DefaultJwtService`).

### 2.2 Monolith seam (per `04-strangler-wiring-design.md`)
- `ExtractionProperties` (`extraction.favorite.enabled=false`, `extraction.favorite.read=monolith|extracted|shadow`, `extraction.favorite.write=monolith|extracted|dual-write`, `extraction.favorite.base-url`, timeouts, `fallback` — see `04-strangler-wiring-design.md` §1).
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
    ... // unchanged: countMap.get(id) unboxes to int -> remote adapter must return a row per id
  }
```

#### 2.2.1 As built (monolith seam)

Flags — `application.properties` (all OFF; env-overridable; a **restart** flips them, no refresh scope in Phase 1):

```properties
extraction.favorite.enabled=${EXTRACTION_FAVORITE_ENABLED:false}
extraction.favorite.read=${EXTRACTION_FAVORITE_READ:monolith}        # monolith|extracted|shadow
extraction.favorite.write=${EXTRACTION_FAVORITE_WRITE:monolith}      # monolith|extracted|dual-write
extraction.favorite.base-url=${EXTRACTION_FAVORITE_BASE_URL:http://localhost:8081}
extraction.favorite.connect-timeout=500ms
extraction.favorite.read-timeout=1500ms
extraction.favorite.fallback=${EXTRACTION_FAVORITE_FALLBACK:monolith} # monolith|empty|fail
extraction.{comment,tag,article,user}.enabled=false
jwt.secret=${JWT_SECRET:<previous value>}
```

| Design name | Built as | Notes |
|---|---|---|
| `ExtractionProperties` | `io.spring.infrastructure.extraction.ExtractionProperties` (+ `ExtractionConfig`) | `DomainRoute` per domain with `readsRemote()/shadows()/writesRemote()/monolithAuthoritative()`; `enabled=false` forces monolith regardless of modes |
| `FavoriteQueryPort` | `io.spring.application.favorite.FavoriteQueryPort` | 4 mirrored methods + `articleIdsFavoritedBy(userId)` + `ownsFavoritedByFilter()` |
| `MyBatisFavoriteQueryAdapter` | `ArticleFavoritesReadService extends FavoriteQueryPort` | the existing mapper *is* the adapter (no wrapper bean, so `@MybatisTest` slices keep working); new select `articleIdsFavoritedBy` |
| `RemoteFavoriteQueryAdapter` | `io.spring.infrastructure.extraction.favorite.RemoteFavoriteQueryAdapter` | empty-list short-circuit, 500-id chunks, zero-fill in request order |
| `RoutingFavoriteQueryPort` | `...extraction.favorite.RoutingFavoriteQueryPort` (`@Primary`) | per-call routing; shadow via `ShadowComparator`/`LoggingShadowComparator` (async, WARN + counters); `fallback` monolith/empty/fail; read-after-write in the same request is served locally while `write != extracted` (`ReadAfterWriteMarker`) |
| `FavoriteCommandPort` | `io.spring.application.favorite.FavoriteCommandPort` | `LocalFavoriteCommand`, `RemoteFavoriteCommand`, `DualWriteFavoriteCommand` (local first; remote failure logged + `pendingMirrorOperations()`), `RoutingFavoriteCommandPort` (`@Primary`) |
| Write seam entry | `RoutingArticleFavoriteRepository` (`@Primary ArticleFavoriteRepository`) | `ArticleFavoriteApi` / `ArticleMutation` are untouched and keep calling `ArticleFavoriteRepository`; `find()` stays on the monolith table while authoritative |
| `FavoriteServiceClient` | `...extraction.favorite.FavoriteServiceClient` | `RestTemplateBuilder` + route timeouts; exactly the §2.1 paths; reads unauthenticated, retried once on connect failure/503 only; writes forward `Authorization` (`AuthTokenPropagator`), never retried; failures → `FavoriteServiceException` |
| DTOs | `io.spring.application.favorite.dto.{FavoriteCountDto,FavoriteCountsDto,UserFavoritesDto,FavoriteDto,ArticleIdsRequest,UserFavoritesQueryRequest}` | |
| `queryArticlesByIds` | `ArticleReadService.{queryArticlesByIds,countArticleByIds,findArticlesWithCursorByIds}` | used only when `FavoriteQueryPort.ownsFavoritedByFilter()`; `ArticleQueryService` resolves username → id with `UserReadService`; join path untouched |

Tests: `src/test/java/io/spring/infrastructure/extraction/**` (unit + `MockRestServiceServer`),
`FavoritedByIdListParityTest` (join vs id-list, offset + cursor), `harness/FavoriteExtractedParallelRunTest`
(`@SpringBootTest`, both `RoutePath`s, EXTRACTED = `enabled=true, read=extracted, write=dual-write` against a
`MockRestServiceServer` stub; goldens byte-identical). Consumer stubs of the §2.1 JSON live in
`src/test/resources/favorite-service-stubs/` (not under `contracts/`, which the Spring Cloud Contract plugin treats as
monolith *producer* contracts). The Phase 0 `FavoriteParallelRunTest` is unchanged (its EXTRACTED params remain skipped by
design since it is fully mocked).

### 2.3 Not changed in Phase 1
Domain classes `ArticleFavorite`/`ArticleFavoriteRepository` and the MyBatis mapper stay in the
monolith until cutover; they back the flag-OFF path and the authoritative store.

## 3. Risks

| Risk | Mitigation |
|------|-----------|
| `favoritedBy` filter changes from one SQL query to id-list resolution; pagination/ordering must stay identical | parallel-run harness compares `GET /articles?favoritedBy=` envelopes (offset and cursor variants) |
| `favoritesCount` is always `0`, never `null`, for an existing article; a missing count row NPEs | golden edge-case test pins the JSON (`ArticleFavoritesReadServiceTest`, `FavoriteQueryServiceTest`); remote adapter fills `0` for ids the service does not return |
| Service unavailable -> article listings degrade | `extraction.favorite.fallback=monolith`: client failure falls back to the local MyBatis adapter while dual-write keeps the local table authoritative (fallback is only removed at cutover) |
| Double-favorite: today idempotent only because `MyBatisArticleFavoriteRepository.save` reads before inserting; raw insert -> `UncategorizedSQLException` | service uses `insert or ignore`; API-level 200 contract preserved (Phase 0 `ArticleFavoriteApiEdgeCaseTest`) |
| Empty id batch produces invalid `IN ()` SQL today; 600-id batches work | both adapters short-circuit empty lists and cap batches at 500 (Phase 0 test) |
| N+1 / latency: each article page now needs 2 remote calls | both calls are batched per page (they already are), timeouts + metrics tagged `route=extracted` |
| JWT forwarding | same secret; contract test for `Token` header acceptance |

## 4. Validation

1. **Golden tests** — all 27 baseline files (`00-golden-test-baseline.md`) pass with flag OFF and with flag ON (`ArticleFavoriteApiTest`, `ArticlesApiTest`, `ListArticleApiTest`, `ArticleQueryServiceTest`, `MyBatisArticleFavoriteRepositoryTest` are the critical ones).
2. **Contract tests** (`01-contract-testing.md`) — consumer (monolith) contracts for the four internal endpoints above; provider verification runs in `favorite-service`.
3. **Edge cases** (Phase 0 Favorite tests): idempotent double-favorite, unfavorite-when-not-favorited (200 no-op), favorite non-existent slug -> 404 on both verbs, `favoritesCount` always `0` not `null`, empty/large id batches, anonymous reads, `favoritedBy` filter.
4. **Parallel run** (`02-parallel-run-harness.md`) — `FavoriteParallelRunTest` MONOLITH vs EXTRACTED for `GET /articles/{slug}`, `GET /articles?favoritedBy=`, `POST/DELETE /articles/{slug}/favorite`, GraphQL `favoriteArticle`.
5. **Reconciliation** — zero drift between `article_favorites` in `dev.db` and `favorite.db` for N consecutive runs before cutover.
6. Both `./gradlew build -x jacocoTestCoverageVerification` (monolith and service) green.

## 5. Step-by-step execution order

1. Create `favorite-service` from template; V1 migration; internal endpoints; provider contract tests. (No monolith change yet.)
2. Backfill `article_favorites` from `dev.db` -> `favorite.db`; run reconciliation report.
3. Monolith: add `ExtractionProperties`, ports, adapters, client (flag OFF). Golden tests green.
4. `extraction.favorite.enabled=true`, `write=dual-write` (writes go local then remote). Reconcile until zero drift.
5. `read=shadow` (parallel-run, mismatches metered), then `read=extracted`. Monitor.
6. Cutover: service becomes authoritative for writes; monolith local write becomes the mirror.
7. Decommission (later, separate approval): remove MyBatis favorite mapper + table from monolith.

## 6. Rollback (any step, no data loss)

1. Flip `extraction.favorite.enabled=false` -> all reads/writes go to the monolith table, which has been continuously written (local-first dual-write). No restart is required if properties are externalized/refreshable; otherwise a restart with the env var unset.
2. Leave `dual-write` on (harmless) or turn off to stop remote calls.
3. If rollback happens after step 6 (service was authoritative), run reverse backfill `favorite.db -> dev.db` for rows written during the window (natural key makes this an idempotent upsert), then reconcile.
4. Because there are no FK constraints, the monolith table can be truncated/re-filled freely without affecting `articles`/`users`.
