# Phase 2 — Close the cursor-pagination gap in REST

## Scope

Expose the Relay-style cursor pagination that was previously GraphQL-only through the existing
REST controllers, reusing the already-existing service methods
`ArticleQueryService.findRecentArticlesWithCursor`, `ArticleQueryService.findUserFeedWithCursor`
and `CommentQueryService.findByArticleIdWithCursor`. The existing offset/limit endpoints are kept
unchanged for backward compatibility.

### Design

Cursor pagination is a **query-parameter variant** of the existing list endpoints, selected by
Spring MVC `params` mapping conditions, so no new URL is introduced (and no slug such as
`/articles/cursor` is shadowed):

| Endpoint | Selector | Handler | Service call |
| --- | --- | --- | --- |
| `GET /articles?first=N[&after=CURSOR][&tag&author&favorited]` | `params = "first"` | `ArticlesApi.getArticlesNextPage` | `findRecentArticlesWithCursor(tag, author, favorited, NEXT)` |
| `GET /articles?last=N[&before=CURSOR][&tag&author&favorited]` | `params = {"last", "!first"}` | `ArticlesApi.getArticlesPreviousPage` | `findRecentArticlesWithCursor(tag, author, favorited, PREV)` |
| `GET /articles?offset&limit…` (unchanged) | default | `ArticlesApi.getArticles` | `findRecentArticles` |
| `GET /articles/feed?first=N[&after=CURSOR]` | `params = "first"` | `ArticlesApi.getFeedNextPage` | `findUserFeedWithCursor(currentUser, NEXT)` |
| `GET /articles/feed?last=N[&before=CURSOR]` | `params = {"last", "!first"}` | `ArticlesApi.getFeedPreviousPage` | `findUserFeedWithCursor(currentUser, PREV)` |
| `GET /articles/feed?offset&limit` (unchanged) | default | `ArticlesApi.getFeed` | `findUserFeed` |
| `GET /articles/{slug}/comments?first=N[&after=CURSOR]` | `params = "first"` | `CommentsApi.getCommentsNextPage` | `findByArticleIdWithCursor(articleId, NEXT)` |
| `GET /articles/{slug}/comments?last=N[&before=CURSOR]` | `params = {"last", "!first"}` | `CommentsApi.getCommentsPreviousPage` | `findByArticleIdWithCursor(articleId, PREV)` |
| `GET /articles/{slug}/comments` (unchanged) | default | `CommentsApi.getComments` | `findByArticleId` |

The GraphQL `Profile.articles`, `Profile.favorites` and `Profile.feed` fields are the same
service calls with `author={username}`, `favorited={username}` and the current user's feed
respectively, so they are served by the `/articles` and `/articles/feed` cursor variants above.

Cursor values are the same opaque strings GraphQL produced (`DateTimeCursor` → epoch millis of
`updatedAt` for articles, `createdAt` for comments). Response shape mirrors `CursorPager`
(`io.spring.api.CursorPageResponse`):

```json
{
  "articles": [ ...ArticleData... ],          // or "comments": [ ...CommentData... ]
  "startCursor": "1700000000000",             // null on an empty page
  "endCursor":   "1699990000000",             // null on an empty page
  "hasNext": true,
  "hasPrevious": false
}
```

Security is unchanged: `/articles/feed` (any params) still requires authentication;
`/articles` and `/articles/{slug}/comments` remain publicly readable.

## Build / test commands

| Command | Outcome |
| --- | --- |
| `./gradlew spotlessApply` | formatted (google-java-format) |
| `./gradlew test --tests 'io.spring.api.*'` | 48 tests, 48 passed, 0 failed, 0 skipped |
| `./gradlew build -x jacocoTestCoverageVerification` | **BUILD SUCCESSFUL** — **81 tests, 81 passed, 0 failed, 0 skipped** (baseline was 68; +13 new) |

## Functionality → REST endpoint → proving test

| Functionality | REST endpoint | Test class / method | Result |
| --- | --- | --- | --- |
| Articles list, next page with cursor + filters (`Query.articles`, `Profile.articles`, `Profile.favorites`) | `GET /articles?first&after&tag&author&favorited` | `CursorArticlesApiTest.should_get_articles_next_page_with_cursor` | PASS |
| Articles list, previous page with cursor | `GET /articles?last&before` | `CursorArticlesApiTest.should_get_articles_previous_page_with_cursor` | PASS |
| Articles list, first page (no cursor value) | `GET /articles?first` | `CursorArticlesApiTest.should_get_first_page_without_cursor_value` | PASS |
| Articles list, empty page → null cursors, no next/prev | `GET /articles?first&after` | `CursorArticlesApiTest.should_get_empty_page_with_null_cursors` | PASS |
| Offset/limit list still used when no cursor params (backward compat) | `GET /articles` | `CursorArticlesApiTest.should_keep_offset_paging_when_no_cursor_params` | PASS |
| Feed, next page with cursor (`Query.feed`, `Profile.feed`) | `GET /articles/feed?first&after` | `CursorArticlesApiTest.should_get_feed_next_page_with_cursor` | PASS |
| Feed, previous page with cursor | `GET /articles/feed?last&before` | `CursorArticlesApiTest.should_get_feed_previous_page_with_cursor` | PASS |
| Feed cursor variant requires authentication | `GET /articles/feed?first` → 401 | `CursorArticlesApiTest.should_get_401_for_cursor_feed_without_login` | PASS |
| Article comments, next page with cursor (`Article.comments`) | `GET /articles/{slug}/comments?first&after` | `CursorCommentsApiTest.should_get_comments_next_page_with_cursor` | PASS |
| Article comments, previous page with cursor | `GET /articles/{slug}/comments?last&before` | `CursorCommentsApiTest.should_get_comments_previous_page_with_cursor` | PASS |
| Article comments, empty page → null cursors | `GET /articles/{slug}/comments?first` | `CursorCommentsApiTest.should_get_empty_comment_page_with_null_cursors` | PASS |
| Article comments cursor variant, unknown slug → 404 | `GET /articles/not-exists/comments?first` | `CursorCommentsApiTest.should_get_404_for_cursor_comments_of_unknown_article` | PASS |
| Full comment list still used when no cursor params (backward compat) | `GET /articles/{slug}/comments` | `CursorCommentsApiTest.should_keep_full_list_when_no_cursor_params` | PASS |

Each cursor test also captures the `CursorPageParameter` passed to the mocked service and asserts
direction (`NEXT`/`PREV`), limit and parsed cursor value, and verifies the offset/limit service
method was **not** called (and vice-versa for the backward-compat tests).

### Pre-existing offset/limit tests (regression check)

| Test class | Tests | Result |
| --- | --- | --- |
| `ListArticleApiTest` (`GET /articles`, `GET /articles/feed` offset/limit, 401) | 3 | PASS |
| `ArticlesApiTest` (`POST /articles`) | 3 | PASS |
| `CommentsApiTest` (`GET/POST/DELETE /articles/{slug}/comments`) | 5 | PASS |
| All other pre-existing classes (see Phase 1 table) | 57 | PASS |

## Regressions

None. All 68 baseline tests still pass alongside the 13 new tests.

Note: the JaCoCo 80% instruction-coverage gate (`jacocoTestCoverageVerification`) is a pre-existing
CI threshold; overall instruction coverage is currently ~35% (3691/10691 instructions), largely
because of the untested DGS-generated `io.spring.graphql.types` classes. Per `AGENTS.md` it is
skipped with `-x jacocoTestCoverageVerification`.

## Conclusion

**Phase complete / safe to proceed.** Cursor pagination is now available over REST for articles,
feed and comments with no change to existing endpoints.
