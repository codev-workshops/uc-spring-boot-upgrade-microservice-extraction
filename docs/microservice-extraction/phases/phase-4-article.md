# Phase 4 — Article extraction

Status: **APPROVED — implementation in progress** (approved after Phase 3 completion; Phase 3 = PR #27).

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

#### Canonical internal API (pinned for implementation; mirrors the Phase 1–3 conventions)

Same `article-service` (port 8083, `article.db`, HS512 `jwt.secret`) from Phase 3. Migrations: the `articles` table already exists in `V1__create_article_tables.sql`; add `V3__seed_articles.sql` with the monolith's `articles` seed rows only. The service has **no user data**: every filter is by id, and the monolith resolves `author`/`favorited` usernames and profile composition itself.

Article row DTO: `{"id","slug","title","description","body","userId","createdAt","updatedAt","tagList":[...]}` — timestamps are ISO-8601 strings produced by the same `DateTimeHandler` semantics as the monolith; `tagList` in `article_tags` rowid order (Phase 3).

| Method / path | Auth | Request | Response |
|---|---|---|---|
| `GET /internal/articles/{id}` | none | — | `200 {"article":row}` / `404` |
| `GET /internal/articles/by-slug/{slug}` | none | — | `200 {"article":row}` / `404` |
| `GET /internal/articles?ids=a,b,c` | none | — | `200 {"articles":[row...]}` ordered `created_at DESC` (mirrors `ArticleReadService.xml#findArticles`); empty `ids` -> `[]` |
| `GET /internal/articles/ids?tag=&authorId=&ids=&offset=&limit=` | none | `ids` = optional article-id allow-list (monolith passes the Favorite-port result for `favorited=`); `authorId` = resolved user id | `200 {"articleIds":[...],"count":N}` — `DISTINCT` ids `ORDER BY created_at DESC LIMIT offset,limit`, `count` = total matching (mirrors `queryArticles`/`countArticle` and the `*ByIds` variants) |
| `GET /internal/articles/ids/cursor?tag=&authorId=&ids=&limit=&direction=next\|prev[&cursor=<millis>]` | none | — | `200 {"articleIds":[...]}` — up to `limit+1` ids with the monolith's `created_at <`/`>` and `DESC`/`ASC` semantics (`findArticlesWithCursor[ByIds]`); the monolith computes `hasNext`/cursor |
| `GET /internal/articles/feed?authorIds=&offset=&limit=` | none | `authorIds` = followed user ids resolved by the monolith | `200 {"articles":[row...],"count":N}` (`findArticlesOfAuthors` + `countFeedSize`); empty `authorIds` -> `{"articles":[],"count":0}` |
| `GET /internal/articles/feed/cursor?authorIds=&limit=&direction=&cursor=` | none | — | `200 {"articles":[row...]}` (`limit+1` semantics of `findArticlesOfAuthorsWithCursor`) |
| `POST /internal/articles` | `Token <jwt>`, `sub == body.userId` else 403; missing/invalid 401 | `{"id","slug","title","description","body","userId","createdAt","tags":[{"id","name"}]}` — id/slug/timestamps/tag ids generated by the caller so dual-write produces identical rows | `201 {"article":row}`; same `id` again -> `200` same row (idempotent); slug already used by a *different* id -> `422 {"errors":{"title":["article name exists"]}}`; tags handled exactly like Phase 3 `PUT .../tags`, in one transaction with the row |
| `PUT /internal/articles/{id}` | `Token <jwt>` (authorization stays in the monolith) | `{"title","description","body","slug"}` — blank fields are skipped exactly like `ArticleMapper.xml#update` (`<if test="!= ''">`), and **`updated_at` is NOT written** (monolith quirk to preserve) | `200 {"article":row}` / `404`; slug conflict with another id -> `422` |
| `DELETE /internal/articles/{id}` | `Token <jwt>` | — | `204` idempotent; does **not** delete `article_tags`, comments or favorites (matches `ArticleMapper.xml#delete`, no FKs) |
| `GET /actuator/health` | none | — | `{"status":"UP"}` |

Errors use the monolith envelope `{"errors":{...}}`. Monolith flags: `extraction.article.{enabled,read,write,base-url,connect-timeout,read-timeout,fallback}` with the Phase 1–3 defaults (`base-url=http://localhost:8083`, shared with the Tag seam). The Phase 3 tag dual-write on article create is superseded when the Article write route is `extracted`/`dual-write` (tags travel inside `POST /internal/articles`); with the Article flag OFF, Phase 3 behaviour is unchanged.

### 2.2 Monolith seam
- `extraction.article.{enabled,read,write,base-url}` (read: monolith|extracted|shadow; write: monolith|extracted|dual-write — see `04-strangler-wiring-design.md` §1.1).
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
