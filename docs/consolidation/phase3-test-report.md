# Phase 3 Test Report — REST parity for all remaining operations and error-contract hardening

## Scope

Phase 3 proves, with REST controller tests, that every operation previously exposed through the
GraphQL layer (`io.spring.graphql`) has an equivalent, tested REST endpoint, and that the REST
validation error contract (`CustomizeExceptionHandler`, HTTP 422 with an `errors` map) fully
replaces the GraphQL `UserResult = UserPayload | Error` union used by `createUser`.

No production code was changed in this phase. Changes are test-only:

- New `src/test/java/io/spring/api/TagsApiTest.java` — `GET /tags` (previously only covered at the
  service level by `TagsQueryServiceTest`).
- Extended `src/test/java/io/spring/api/ProfileApiTest.java` — profile 404, unauthenticated follow
  (401), unfollow of a user that is not followed (404).
- Extended `src/test/java/io/spring/api/UsersApiTest.java` — two explicit error-contract tests:
  - `should_return_422_with_errors_map_for_every_invalid_register_field` — request-level
    validation (`@Valid RegisterParam` → `MethodArgumentNotValidException`) yields status 422, a
    body whose only top-level key is `errors`, and one entry per invalid field
    (`email`, `username`, `password`) with the same messages the GraphQL `Error.errors[]` carried.
  - `should_map_service_constraint_violation_to_422_errors_map` — service-level validation
    (`@Validated UserService.createUser` → `ConstraintViolationException`, the exact exception the
    GraphQL `UserMutation` caught to build the union `Error`) is translated by
    `CustomizeExceptionHandler.handleConstraintViolation` into the same 422 + `errors` map, with
    the method-parameter path prefix (`createUser.registerParam.`) stripped to the field name.

## Commands and outcomes

| Command | Outcome |
|---|---|
| `./gradlew spotlessApply` | PASS (google-java-format applied) |
| `./gradlew test --tests 'io.spring.api.*' -x jacocoTestCoverageVerification --console=plain` | PASS — 55 controller tests, 0 failed, 0 skipped |
| `./gradlew build -x jacocoTestCoverageVerification --console=plain` | PASS — **88 total, 88 passed, 0 failed, 0 skipped** |

The global JaCoCo threshold is skipped per `AGENTS.md` (pre-existing gate, not in scope).

Baseline was 68 tests (Phase 1) → 81 (Phase 2) → 88 (Phase 3). All prior tests still pass.

## GraphQL operation → REST endpoint → proving test

| GraphQL operation (removed in Phase 4) | REST endpoint | Test class / method | Result |
|---|---|---|---|
| `Mutation.createArticle` | `POST /articles` | `ArticlesApiTest.should_create_article_success` | PASS |
| `Mutation.createArticle` validation | `POST /articles` → 422 | `ArticlesApiTest.should_get_error_message_with_wrong_parameter`, `should_get_error_message_with_duplicated_title` | PASS |
| `Mutation.updateArticle` | `PUT /articles/{slug}` | `ArticleApiTest.should_update_article_content_success`, `should_get_403_if_not_author_to_update_article` | PASS |
| `Mutation.deleteArticle` | `DELETE /articles/{slug}` | `ArticleApiTest.should_delete_article_success`, `should_403_if_not_author_delete_article` | PASS |
| `Query.article(slug)` | `GET /articles/{slug}` | `ArticleApiTest.should_read_article_success`, `should_404_if_article_not_found` | PASS |
| `Query.articles(first/after/last/before, …)` | `GET /articles?first=&after=` / `?last=&before=` | `CursorArticlesApiTest` (8 tests, Phase 2) | PASS |
| `Query.articles` (offset compat) | `GET /articles?offset=&limit=` | `ListArticleApiTest.should_get_default_article_list`, `CursorArticlesApiTest.should_keep_offset_paging_when_no_cursor_params` | PASS |
| `Query.feed` | `GET /articles/feed?first=` / `?last=` and offset variant | `CursorArticlesApiTest` feed tests, `ListArticleApiTest.should_get_feeds_success`, `should_get_feeds_401_without_login` | PASS |
| `Mutation.favoriteArticle` | `POST /articles/{slug}/favorite` | `ArticleFavoriteApiTest.should_favorite_an_article_success` | PASS |
| `Mutation.unfavoriteArticle` | `DELETE /articles/{slug}/favorite` | `ArticleFavoriteApiTest.should_unfavorite_an_article_success` | PASS |
| `Mutation.addComment` | `POST /articles/{slug}/comments` | `CommentsApiTest.should_create_comment_success`, `should_get_422_with_empty_body` | PASS |
| `Article.comments(first/after/last/before)` | `GET /articles/{slug}/comments?first=` / `?last=` | `CursorCommentsApiTest` (5 tests, Phase 2) | PASS |
| `Article.comments` (full list compat) | `GET /articles/{slug}/comments` | `CommentsApiTest.should_get_comments_of_article_success` | PASS |
| `Mutation.deleteComment` | `DELETE /articles/{slug}/comments/{id}` | `CommentsApiTest.should_delete_comment_success`, `should_get_403_if_not_author_of_article_or_author_of_comment_when_delete_comment` | PASS |
| `Mutation.createUser` (success) | `POST /users` → 201 | `UsersApiTest.should_create_user_success` | PASS |
| `Mutation.createUser` → `Error` union | `POST /users` → 422 `{errors:{field:[msg]}}` | `UsersApiTest.should_return_422_with_errors_map_for_every_invalid_register_field`, `should_map_service_constraint_violation_to_422_errors_map`, `should_show_error_message_for_blank_username`, `should_show_error_message_for_invalid_email`, `should_show_error_for_duplicated_username`, `should_show_error_for_duplicated_email` | PASS |
| `Mutation.login` | `POST /users/login` | `UsersApiTest.should_login_success`, `should_fail_login_with_wrong_password` | PASS |
| `Mutation.updateUser` | `PUT /user` | `CurrentUserApiTest.should_update_current_user_profile`, `should_get_error_if_email_exists_when_update_user_profile`, `should_get_401_if_not_login` | PASS |
| `Query.me` | `GET /user` | `CurrentUserApiTest.should_get_current_user_with_token`, `should_get_401_without_token`, `should_get_401_with_invalid_token` | PASS |
| `Query.profile(username)` | `GET /profiles/{username}` | `ProfileApiTest.should_get_user_profile_success`, `should_get_404_for_unknown_profile` | PASS |
| `Mutation.followUser` | `POST /profiles/{username}/follow` | `ProfileApiTest.should_follow_user_success`, `should_get_401_when_follow_without_login` | PASS |
| `Mutation.unfollowUser` | `DELETE /profiles/{username}/follow` | `ProfileApiTest.should_unfollow_user_success`, `should_get_404_when_unfollow_user_not_followed` | PASS |
| `Query.tags` | `GET /tags` | `TagsApiTest.should_get_all_tags_without_login`, `should_get_empty_tag_list` | PASS |
| `Profile.articles` / `Profile.favorites` | `GET /articles?author=` / `?favorited=` (offset or cursor) | `CursorArticlesApiTest` (filters forwarded to `findRecentArticlesWithCursor`), `ListArticleApiTest.should_get_default_article_list` | PASS |
| `Profile.feed` | `GET /articles/feed` (offset or cursor) | `CursorArticlesApiTest` feed tests, `ListArticleApiTest.should_get_feeds_success` | PASS |

Coverage: 100% of GraphQL queries, mutations and field resolvers have a REST endpoint with at
least one passing controller test.

## Error contract confirmation

REST validation contract via `io.spring.api.exception.CustomizeExceptionHandler`:

```json
HTTP 422 Unprocessable Entity
{"errors": {"email": ["should be an email"], "username": ["can't be empty"], "password": ["can't be empty"]}}
```

This carries the same information as the GraphQL `Error { message, errors: [{ key, value[] }] }`
union member (field key → list of messages), so the GraphQL union type and
`GraphQLCustomizeExceptionHandler` can be removed without loss of functionality.

## Regressions

None. One transient test-authoring issue (Mockito `UnfinishedStubbingException` caused by building
a mocked `ConstraintViolation` inside a `when(...)` call) was fixed by constructing the exception
before stubbing; not a product regression.

## Conclusion

**Phase complete / safe to proceed to Phase 4 (GraphQL layer removal).**
