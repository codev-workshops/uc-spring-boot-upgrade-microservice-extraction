# Phase 4 — Article extraction

Status: **design, awaiting approval**. Starts only after Phase 3 is confirmed complete and this doc is approved.

## 1. Scope

Complete `article-service` (port 8083, DB `article.db`, created in Phase 3) by moving the `articles`
table and article commands/queries into it. After this phase the monolith keeps only the User domain
plus the public HTTP/GraphQL façade (strangler shell).

Owned data: `articles (id, user_id, slug UNIQUE, title, description, body, created_at, updated_at)` + tag tables from Phase 3.

### Monolith touch points

| Layer | Class / file | Usage |
|-------|--------------|-------|
| api | `ArticleApi` (`GET/PUT/DELETE /articles/{slug}`), `ArticlesApi` (`POST /articles`, `GET /articles`, `GET /articles/feed`) | `ArticleCommandService`, `ArticleQueryService`, `ArticleRepository`, `AuthorizationService.canWriteArticle` |
| api | `ArticleFavoriteApi`, `CommentsApi` — resolve `slug -> Article` via `ArticleRepository.findBySlug` | cross-domain lookup becomes `ArticleServiceClient.findIdBySlug` |
| graphql | `ArticleDatafetcher`, `ArticleMutation` | same |
| application | `ArticleCommandService`, `DuplicatedArticleValidator` (slug uniqueness check), `ArticleQueryService` (+ `UserRelationshipQueryService.followingAuthors` for `profileData.following`, `findUserFeed` by followed user ids) | |
| infrastructure | `MyBatisArticleRepository`, `ArticleMapper.xml`, `ArticleReadService.xml` (joins `users` for `profileData` and `article_favorites` for `favoritedBy` — the latter already routed in Phase 1) | |
| core | `Article`, `ArticleRepository`, `Tag` | |

## 2. Planned changes

### 2.1 `article-service`
- Extend V1 schema use (table already exists from Phase 3); backfill `articles`.
- Internal API: `GET /internal/articles/{id}`, `GET /internal/articles/by-slug/{slug}`, `GET /internal/articles?tag=&authorId=&ids=&offset=&limit=` and cursor variant, `GET /internal/articles/feed?authorIds=`, `POST /internal/articles`, `PUT /internal/articles/{id}`, `DELETE /internal/articles/{id}`; returns article rows + `tagList` (**no** `profileData`, `favorited`, `favoritesCount`).
- Slug generation (`Article.toSlug`) and uniqueness (`DuplicatedArticleValidator`) move into the service; the monolith validator calls the service when flag ON.
- `ArticleServiceClient` in the service? No — per AGENTS.md the *caller* owns the client: the service gets a `UserServiceClient` only if it needs user data (it does not; profile composition stays in the monolith façade).

### 2.2 Monolith seam
- `extraction.article.{enabled,dual-write,base-url}`.
- `ArticleQueryPort` / `ArticleCommandPort` with `MyBatis`, `Remote`, `DualWrite` adapters.
- `ArticleQueryService` becomes a composer: article rows (port) + author profiles (`UserReadService`, local) + `following` (local) + favorites (Phase 1 port) + tags (Phase 3 port). `ArticleData` JSON must be byte-identical.
- `ArticleFavoriteApi`, `CommentsApi`, `ArticleMutation`, `CommentMutation`: `articleRepository.findBySlug(slug)` replaced by `ArticleLookupPort.findBySlug` returning a light `ArticleRef(id, userId, slug)` used for 404 and `AuthorizationService` checks (signature of `canWriteArticle(User, Article)` kept via an adapter or an overload — the Phase 0 unit tests pin behaviour).
- `findUserFeed` (feed by followed authors): monolith resolves followed user ids locally and calls `GET /internal/articles/feed?authorIds=`.

## 3. Risks

| Risk | Mitigation |
|------|-----------|
| Largest read model; `ArticleData` composed from 4 sources | parallel-run on every article endpoint including `feed`, cursor + offset paging |
| Pagination parity (`articlesCount`, `hasNext`, `DateTimeCursor`) | service reproduces `limit+1` semantics; golden `ListArticleApiTest` |
| Slug uniqueness across dual-write window (race) | slug decided locally first, remote insert with the same id/slug (idempotent by id) |
| Transactional save of article + tags (`ArticleRepositoryTransactionTest`) | inside the service, a single local transaction; monolith flag-OFF path unchanged |
| Author profile `following` computation | stays local in this phase (users not yet extracted) |
| GraphQL DGS datafetchers with nested resolution | same ports; DGS tests via `RealworldApplicationTests` + parallel-run GraphQL queries |

## 4. Validation
1. Golden: `ArticleApiTest`, `ArticlesApiTest`, `ListArticleApiTest`, `ArticleQueryServiceTest`, `ArticleTest`, `MyBatisArticleRepositoryTest`, `ArticleRepositoryTransactionTest`, plus all Favorite/Comment tests (they depend on slug lookup).
2. Contracts for every internal endpoint above (consumer: monolith; provider: article-service).
3. Edge cases: duplicate title -> 422 envelope, update slug regeneration, delete cascades (comments/favorites of a deleted article are NOT deleted today — no FK — preserve), feed with no followed users, anonymous reads.
4. Parallel run for REST + GraphQL article surface.
5. Reconciliation on `articles` by `id` (and `slug`).

## 5. Execution order
1. Service endpoints + provider contracts. 2. Backfill `articles`. 3. Monolith ports (flag OFF), slug-lookup port used by Favorite/Comment APIs. 4. Dual-write, reconcile. 5. Reads ON. 6. Cutover. 7. Decommission later.

## 6. Rollback
1. `extraction.article.enabled=false` -> local `articles` (authoritative, dual-written).
2. Post-cutover: reverse backfill by `id` (`slug` UNIQUE — reconcile conflicts by id first).
3. Comments/favorites reference `article_id` only by value (no FK), so they are unaffected by re-populating `articles`.
