# Phase 3 — Tag extraction (bundled with Article)

Status: **design, awaiting approval**. Starts only after Phase 2 is confirmed complete and this doc is approved.

## 1. Scope and bundling rationale

Tags have no standalone aggregate: `Tag` lives in `io.spring.core.article`, tags are created only as a
side effect of `ArticleRepository.save(article)` (`ArticleMapper.xml#insertTag / insertArticleTagRelation`),
and every article read joins `article_tags`/`tags` (`ArticleReadService.xml#selectArticleData`).
Therefore Tag is extracted **into the same `article-service`** that Phase 4 completes. Phase 3 stands up
`article-service` with the tag tables and the read-only tag API; Phase 4 moves `articles` into it.

Owned data (Phase 3): `tags (id, name)`, `article_tags (article_id, tag_id)`. Seed: tag rows + relations.

### Monolith touch points

| Layer | Class / file | Usage |
|-------|--------------|-------|
| api | `io.spring.api.TagsApi` (`GET /tags`) | `TagsQueryService.allTags()` -> `TagReadService.all()` |
| graphql | `io.spring.graphql.TagDatafetcher` | same |
| application | `ArticleQueryService` (via `ArticleReadService.xml` joins for `tagList` and the `tag=` filter) | read joins |
| infrastructure | `MyBatisArticleRepository` (`insertTag`, `insertArticleTagRelation`, `findTag`, `deleteArticleTagRelation`-style statements in `ArticleMapper.xml`) | writes as part of article save/update |
| core | `io.spring.core.article.Tag`, `Article.tags` | domain |

## 2. Planned changes

### 2.1 `article-service` (created now, completed in Phase 4) — port 8083, DB `article.db`
- `V1__create_article_tables.sql` containing `articles`, `tags`, `article_tags` (articles table created now but left empty until Phase 4 backfill; this keeps `V1__` naming and avoids a later schema migration). Seed tags + article_tags.
- Internal API (Phase 3):
  - `GET /internal/tags` -> `{"tags":[...]}` (same ordering as `select name from tags`)
  - `GET /internal/articles/tags?articleIds=` -> `{"articleTags":[{"articleId","tagList":[...]}]}`
  - `GET /internal/tags/{name}/article-ids` (for the `tag=` filter)
  - `PUT /internal/articles/{articleId}/tags` `{"tagList":[...]}` (idempotent set; creates missing tags)

### 2.2 Monolith seam
- `extraction.tag.{enabled,read,write,base-url}` (read: monolith|extracted|shadow; write: monolith|extracted|dual-write — see `04-strangler-wiring-design.md` §1.1).
- `TagQueryPort` (`MyBatis` / `Remote`) used by `TagsQueryService`.
- `ArticleQueryService`: when tag flag ON, `tagList` is filled from the port after `findArticles` (mirrors how `favoritesCount` is filled) and the `tag=` filter is resolved to article ids; when OFF, existing SQL joins.
- `ArticleCommandService`/`MyBatisArticleRepository.save`: `DualWriteTagCommand` mirrors the tag set to the service after the local transaction commits (`ArticleRepositoryTransactionTest` covers the local transactional behaviour that must not change).

## 3. Risks

| Risk | Mitigation |
|------|-----------|
| Tag ordering in `tagList` (currently join order, effectively insertion order) | contract + parallel-run pin the order; service returns in `article_tags` rowid order |
| Tag de-duplication by name (`findTag` by name; `tags.id` is a UUID) | service enforces unique name (`insert or ignore` on name) — DB has no unique constraint today, keep the same semantics |
| Two-phase creation (article locally, tags remotely) on article create | local-first; remote failure -> reconcile; article response uses local data while flag OFF |
| Tests that assert `tagList` via `@WebMvcTest` mocks (`ArticleApiTest`, `ArticlesApiTest`) | unchanged, they mock `ArticleQueryService` |

## 4. Validation
1. Golden: `TagsQueryServiceTest`, `ArticleQueryServiceTest`, `MyBatisArticleRepositoryTest`, `ArticleRepositoryTransactionTest`, `ListArticleApiTest`.
2. Contracts for the four internal endpoints.
3. Edge cases: article with no tags (`tagList: []`), duplicate tags in `NewArticleParam`, `tag=` filter with unknown tag (empty list, `articlesCount: 0`), very long tag lists.
4. Parallel run: `GET /tags`, `GET /articles?tag=`, `GET /articles/{slug}` tagList, `POST /articles` with tags.
5. Reconciliation on `tags` (by name) and `article_tags` (by pair).

## 5. Execution order
1. `article-service` skeleton with tag tables/endpoints. 2. Backfill tags + article_tags. 3. Monolith ports (flag OFF). 4. Dual-write, reconcile. 5. Reads ON. 6. Cutover for tags. Articles remain in monolith until Phase 4.

## 6. Rollback
1. `extraction.tag.enabled=false` -> SQL joins on the monolith's `tags`/`article_tags` (still dual-written and authoritative).
2. Post-cutover: reverse backfill of tag pairs (idempotent by `(article_id, tag_id)`); tags by name.
3. No FKs: `article_tags` can be rebuilt without touching `articles`.
