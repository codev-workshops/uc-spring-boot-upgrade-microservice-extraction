# Phase 2 — Comment extraction

Status: **design, awaiting approval**. Starts only after Phase 1 is confirmed complete and this doc is approved.

## 1. Scope

Extract the Comment domain into `comment-service` (proposed port 8082, DB `comment.db`).

Owned data: table `comments (id, body, article_id, user_id, created_at, updated_at)`.
Seed: `comments` rows in `V2__seed_data.sql`.

### Monolith touch points

| Layer | Class / file | Usage |
|-------|--------------|-------|
| api | `io.spring.api.CommentsApi` | `POST /articles/{slug}/comments`, `GET .../comments`, `DELETE .../comments/{id}`; resolves slug via `ArticleRepository`, checks `AuthorizationService.canWriteComment(user, article, comment)` |
| graphql | `io.spring.graphql.CommentMutation`, `CommentDatafetcher` | same create/delete + cursor listing |
| application | `io.spring.application.CommentQueryService` | `CommentReadService` + `UserRelationshipQueryService.followingAuthors/isUserFollowing` to fill `profileData.following` |
| infrastructure | `MyBatisCommentRepository`, `CommentMapper(.xml)`, `CommentReadService(.xml)` | persistence; `CommentReadService.xml` **joins `users`** for the author profile (`profileColumns`) |
| core | `io.spring.core.comment.Comment`, `CommentRepository` | domain |
| resources | `mapper/TransferData.xml#commentData` | result map (note: `updatedAt` is mapped from `commentCreatedAt` — a quirk to preserve) |

## 2. Planned changes

### 2.1 `comment-service`
- Template-based project; `V1__create_comment_tables.sql` (`comments` only) + seed.
- Internal API (wrapped envelopes, monolith error format):
  - `GET  /internal/articles/{articleId}/comments` and cursor variant `?cursor=&limit=&direction=` returning raw comment rows (`id, body, articleId, userId, createdAt`) — **no profile data**
  - `GET  /internal/comments/{id}` (with `articleId` check)
  - `POST /internal/articles/{articleId}/comments` `{"body","userId"}` -> 201
  - `DELETE /internal/articles/{articleId}/comments/{id}` -> 204
- Authorization (article-author-or-comment-author) stays in the monolith at first because the service does not know article authors; the service only enforces "comment.userId == caller" when called directly. Decision recorded in §3.

### 2.2 Monolith seam
- `extraction.comment.{enabled,dual-write,base-url}` in `ExtractionProperties`.
- `CommentQueryPort` (`MyBatisCommentQueryAdapter` / `RemoteCommentQueryAdapter`) and `CommentCommandPort` (`Local` / `Remote` / `DualWrite`).
- `CommentQueryService` is refactored to compose the response: comment rows from the port, author profiles from `UserReadService` (still local in Phase 2), `following` from `UserRelationshipQueryService`. The composed `CommentData` must serialize identically (order of fields, `updatedAt == createdAt` quirk).
- Cursor pagination (`findByArticleIdWithCursor`, `CursorPager`) must produce identical `hasNext`/cursor values: the service reproduces the `created_at <`/`>` + `limit+1` semantics.

## 3. Risks

| Risk | Mitigation |
|------|-----------|
| Profile join moves from SQL to in-process composition; N+1 on profiles | batch `UserReadService.findByIds`-style query (new local mapper method, monolith only) |
| Cursor pagination parity (DateTime cursor encoding `DateTimeCursor`) | parallel-run test on `GET /articles/{slug}/comments` with cursor params (REST + GraphQL) |
| Authorization split (403 for non-author / non-article-author) | `AuthorizationService` unit tests from Phase 0 + `CommentsApiTest`; auth remains in monolith until Article is extracted |
| Deleting a comment whose article was removed (no FK) | today it 404s at slug lookup; keep behaviour |
| Time-zone/format of `createdAt` (`DateTimeHandler`) across services | contract test pins ISO-8601 string; service reuses the same type handler |

## 4. Validation

1. Golden: `CommentsApiTest`, `CommentQueryServiceTest`, `MyBatisCommentRepositoryTest`, `RealworldApplicationTests` flag OFF and ON.
2. Contracts: consumer contracts for the four internal endpoints; provider verification in `comment-service`.
3. Edge cases (to add during Phase 2, harness ready from Phase 0): empty comment list, cursor at boundaries, delete by article author vs. comment author vs. stranger (403), comment on non-existent slug (404), comment body validation (422 envelope).
4. Parallel run: MONOLITH vs EXTRACTED for the three REST endpoints and GraphQL `comments` connection.
5. Reconciliation: zero drift on `comments` by `id`.

## 5. Execution order
1. Service + V1 + endpoints + provider contracts. 2. Backfill `comments`. 3. Monolith ports/adapters flag OFF. 4. Dual-write on, reconcile. 5. Reads on (shadow, then live). 6. Cutover. 7. Decommission later.

## 6. Rollback
1. `extraction.comment.enabled=false` -> monolith `comments` table (continuously dual-written, authoritative) serves everything.
2. Stop dual-write if desired.
3. Post-cutover: reverse backfill by `id` (upsert) `comment.db -> dev.db`, then reconcile.
4. No FKs: `comments` can be re-populated independently of `articles`/`users`.
