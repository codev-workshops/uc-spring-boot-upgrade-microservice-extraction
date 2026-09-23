# Phase 1 — Baseline and Inventory (no behavior change)

## Scope

Capture a green baseline of the existing build/test suite and produce a complete inventory
mapping every GraphQL query, mutation and field resolver in `io.spring.graphql` to its REST
equivalent in `io.spring.api`. No source code was changed in this phase.

## Build / test commands

| Command | Outcome |
| --- | --- |
| `./gradlew build -x jacocoTestCoverageVerification` | **BUILD SUCCESSFUL** (JUnit task `test`, Selenium excluded per `build.gradle`) |

JUnit results (`build/test-results/test/*.xml`): **68 tests, 68 passed, 0 failed, 0 skipped**.

| Test class | Tests | Result |
| --- | --- | --- |
| io.spring.RealworldApplicationTests | 1 | PASS |
| io.spring.api.ArticleApiTest | 6 | PASS |
| io.spring.api.ArticleFavoriteApiTest | 2 | PASS |
| io.spring.api.ArticlesApiTest | 3 | PASS |
| io.spring.api.CommentsApiTest | 5 | PASS |
| io.spring.api.CurrentUserApiTest | 6 | PASS |
| io.spring.api.ListArticleApiTest | 3 | PASS |
| io.spring.api.ProfileApiTest | 3 | PASS |
| io.spring.api.UsersApiTest | 7 | PASS |
| io.spring.application.article.ArticleQueryServiceTest | 9 | PASS |
| io.spring.application.comment.CommentQueryServiceTest | 2 | PASS |
| io.spring.application.profile.ProfileQueryServiceTest | 1 | PASS |
| io.spring.application.tag.TagsQueryServiceTest | 1 | PASS |
| io.spring.core.article.ArticleTest | 5 | PASS |
| io.spring.infrastructure.article.ArticleRepositoryTransactionTest | 1 | PASS |
| io.spring.infrastructure.article.MyBatisArticleRepositoryTest | 3 | PASS |
| io.spring.infrastructure.comment.MyBatisCommentRepositoryTest | 1 | PASS |
| io.spring.infrastructure.favorite.MyBatisArticleFavoriteRepositoryTest | 2 | PASS |
| io.spring.infrastructure.service.DefaultJwtServiceTest | 3 | PASS |
| io.spring.infrastructure.user.MyBatisUserRepositoryTest | 4 | PASS |

Note: the Selenium suite (`src/test/java/io/spring/selenium`, TestNG, task `seleniumTest`) requires a
running application, a browser and a WebDriver download and is not part of `./gradlew build`.
There are no GraphQL tests in `src/test`; every existing test already targets REST or the
service/infrastructure layers. The Next.js frontend (`frontend/`) calls only the REST API.

## GraphQL → REST inventory

### Queries

| GraphQL operation (resolver) | Service call | REST equivalent | Existing REST test | Status |
| --- | --- | --- | --- | --- |
| `Query.article(slug)` (`ArticleDatafetcher.findArticleBySlug`) | `ArticleQueryService.findBySlug` | `GET /articles/{slug}` (`ArticleApi.article`) | `ArticleApiTest.should_read_article_success`, `should_404_if_article_not_found` | Covered |
| `Query.articles(first/after/last/before, authoredBy, favoritedBy, withTag)` (`ArticleDatafetcher.getArticles`) | `ArticleQueryService.findRecentArticlesWithCursor` | `GET /articles?offset&limit&tag&author&favorited` (`ArticlesApi.getArticles`) uses offset/limit | `ListArticleApiTest.should_get_default_article_list` | **REST gap: cursor pagination** |
| `Query.feed(first/after/last/before)` (`ArticleDatafetcher.getFeed`) | `ArticleQueryService.findUserFeedWithCursor` | `GET /articles/feed?offset&limit` (`ArticlesApi.getFeed`) uses offset/limit | `ListArticleApiTest.should_get_feeds_success`, `should_get_feeds_401_without_login` | **REST gap: cursor pagination** |
| `Query.me` (`MeDatafetcher.getMe`) | `UserQueryService.findById` + `JwtService.toToken` | `GET /user` (`CurrentUserApi.currentUser`) | `CurrentUserApiTest.should_get_current_user_with_token`, `should_get_401_*` | Covered |
| `Query.profile(username)` (`ProfileDatafetcher.queryProfile`) | `ProfileQueryService.findByUsername` | `GET /profiles/{username}` (`ProfileApi.getProfile`) | `ProfileApiTest.should_get_user_profile_success` | Covered |
| `Query.tags` (`TagDatafetcher.getTags`) | `TagsQueryService.allTags` | `GET /tags` (`TagsApi.getTags`) | none (service-level `TagsQueryServiceTest` only) | Covered (controller test added in Phase 3) |

### Mutations

| GraphQL operation (resolver) | Service call | REST equivalent | Existing REST test | Status |
| --- | --- | --- | --- | --- |
| `Mutation.createUser(input): UserResult = UserPayload \| Error` (`UserMutation.createUser`) | `UserService.createUser` | `POST /users` (`UsersApi.createUser`) → 201 `{user}` / 422 `{errors:{field:[msg]}}` via `CustomizeExceptionHandler.handleConstraintViolation` | `UsersApiTest.should_create_user_success`, `should_show_error_message_for_blank_username`, `should_show_error_message_for_invalid_email`, `should_show_error_for_duplicated_username`, `should_show_error_for_duplicated_email` | **Contract gap: GraphQL union `Error` type** — REST already returns 422 + errors map (verified by `UsersApiTest`, `ArticlesApiTest.should_get_error_message_with_wrong_parameter`); explicit contract test added in Phase 3 |
| `Mutation.login(email, password)` (`UserMutation.login`) | `UserRepository.findByEmail` + `PasswordEncoder.matches` | `POST /users/login` (`UsersApi.userLogin`) | `UsersApiTest.should_login_success`, `should_fail_login_with_wrong_password` | Covered |
| `Mutation.updateUser(changes)` (`UserMutation.updateUser`) | `UserService.updateUser` | `PUT /user` (`CurrentUserApi.updateProfile`) | `CurrentUserApiTest.should_update_current_user_profile`, `should_get_error_if_email_exists_when_update_user_profile` | Covered |
| `Mutation.followUser(username)` (`RelationMutation.follow`) | `UserRepository.saveRelation` + `ProfileQueryService.findByUsername` | `POST /profiles/{username}/follow` (`ProfileApi.follow`) | `ProfileApiTest.should_follow_user_success` | Covered |
| `Mutation.unfollowUser(username)` (`RelationMutation.unfollow`) | `UserRepository.removeRelation` | `DELETE /profiles/{username}/follow` (`ProfileApi.unfollow`) | `ProfileApiTest.should_unfollow_user_success` | Covered |
| `Mutation.createArticle(input)` (`ArticleMutation.createArticle`) | `ArticleCommandService.createArticle` | `POST /articles` (`ArticlesApi.createArticle`) | `ArticlesApiTest.should_create_article_success`, `should_get_error_message_with_wrong_parameter`, `should_get_error_message_with_duplicated_title` | Covered |
| `Mutation.updateArticle(slug, changes)` (`ArticleMutation.updateArticle`) | `ArticleCommandService.updateArticle` | `PUT /articles/{slug}` (`ArticleApi.updateArticle`) | `ArticleApiTest.should_update_article_content_success`, `should_get_403_if_not_author_to_update_article` | Covered |
| `Mutation.favoriteArticle(slug)` (`ArticleMutation.favoriteArticle`) | `ArticleFavoriteRepository.save` | `POST /articles/{slug}/favorite` (`ArticleFavoriteApi.favoriteArticle`) | `ArticleFavoriteApiTest.should_favorite_an_article_success` | Covered |
| `Mutation.unfavoriteArticle(slug)` (`ArticleMutation.unfavoriteArticle`) | `ArticleFavoriteRepository.remove` | `DELETE /articles/{slug}/favorite` (`ArticleFavoriteApi.unfavoriteArticle`) | `ArticleFavoriteApiTest.should_unfavorite_an_article_success` | Covered |
| `Mutation.deleteArticle(slug): DeletionStatus` (`ArticleMutation.deleteArticle`) | `ArticleRepository.remove` | `DELETE /articles/{slug}` → 204 (`ArticleApi.deleteArticle`) | `ArticleApiTest.should_delete_article_success`, `should_403_if_not_author_delete_article` | Covered |
| `Mutation.addComment(slug, body)` (`CommentMutation.createComment`) | `CommentRepository.save` + `CommentQueryService.findById` | `POST /articles/{slug}/comments` (`CommentsApi.createComment`) | `CommentsApiTest.should_create_comment_success`, `should_get_422_with_empty_body` | Covered |
| `Mutation.deleteComment(slug, id): DeletionStatus` (`CommentMutation.removeComment`) | `CommentRepository.remove` | `DELETE /articles/{slug}/comments/{id}` → 204 (`CommentsApi.deleteComment`) | `CommentsApiTest.should_delete_comment_success`, `should_get_403_if_not_author_of_article_or_author_of_comment_when_delete_comment` | Covered |

### Field resolvers

| GraphQL field (resolver) | REST equivalent | Status |
| --- | --- | --- |
| `Article.author` (`ProfileDatafetcher.getAuthor`) | Embedded `author` object in `ArticleData` (`@JsonProperty("author") ProfileData`) | Covered |
| `Article.comments(first/after/last/before)` (`CommentDatafetcher.articleComments`) → `CommentQueryService.findByArticleIdWithCursor` | `GET /articles/{slug}/comments` (`CommentsApi.getComments`) returns the full list | **REST gap: cursor pagination** |
| `Comment.author` (`ProfileDatafetcher.getCommentAuthor`) | Embedded `author` object in `CommentData` | Covered |
| `Comment.article` (`ArticleDatafetcher.getCommentArticle`) | Comments are addressed under `/articles/{slug}/comments`; article via `GET /articles/{slug}` | Covered |
| `User.profile` (`ProfileDatafetcher.getUserProfile`) | `GET /profiles/{username}` | Covered |
| `Profile.articles(first/after/last/before)` (`ArticleDatafetcher.userArticles`) → `findRecentArticlesWithCursor(null, username, null, …)` | `GET /articles?author={username}` (offset/limit) | **REST gap: cursor pagination** |
| `Profile.favorites(first/after/last/before)` (`ArticleDatafetcher.userFavorites`) → `findRecentArticlesWithCursor(null, null, username, …)` | `GET /articles?favorited={username}` (offset/limit) | **REST gap: cursor pagination** |
| `Profile.feed(first/after/last/before)` (`ArticleDatafetcher.userFeed`) → `findUserFeedWithCursor(targetUser, …)` | `GET /articles/feed` (offset/limit, current user only) | **REST gap: cursor pagination** (see note) |
| `ArticlePayload.article`, `CommentPayload.comment`, `UserPayload.user` (`ArticleDatafetcher.getArticle`, `CommentDatafetcher.getComment`, `MeDatafetcher.getUserPayloadUser`) | REST response envelopes `{"article": …}`, `{"comment": …}`, `{"user": …}` | Covered |

Note on `Profile.feed`: the GraphQL resolver computes the feed of the *profile being viewed*
(articles by users that profile follows). REST `GET /articles/feed` is, per the RealWorld spec and
the frontend, the feed of the *authenticated* user. The RealWorld frontend only ever requests the
current user's feed, so REST parity is provided for the current user's feed (offset and, after
Phase 2, cursor); viewing another user's feed is not exposed by REST.

### Cross-cutting

| Concern | GraphQL | REST | Status |
| --- | --- | --- | --- |
| Authentication | `SecurityUtil.getCurrentUser()` / `graphql.exception.AuthenticationException` | `@AuthenticationPrincipal User` + `WebSecurityConfig` (401 via `JwtTokenFilter`) | Covered |
| Validation errors | `GraphQLCustomizeExceptionHandler` (union `Error` / `BAD_REQUEST` extensions) | `CustomizeExceptionHandler` → 422 `{"errors": {field: [messages]}}` | Covered (explicit test in Phase 3) |
| Not found / forbidden | `ResourceNotFoundException` / `NoAuthorizationException` bubbled through DGS default handler | Same exceptions → 404 / 403 via `@ResponseStatus` | Covered |

## Confirmed gaps

The only true functional gaps between GraphQL and REST are:

1. Cursor (Relay-style) pagination on: articles list, feed, article comments, `Profile.articles`,
   `Profile.favorites`, `Profile.feed` (all of which reuse `findRecentArticlesWithCursor`,
   `findUserFeedWithCursor` and `CommentQueryService.findByArticleIdWithCursor`).
2. The GraphQL union error contract for `createUser` (`UserResult = UserPayload | Error`); REST
   already expresses the same information as HTTP 422 with an `errors` map.

Everything else is a thin adapter over the same `io.spring.application` services.

## Regressions

None (no code changed).

## Conclusion

**Phase complete / safe to proceed.** Baseline is green (68/68) and the inventory confirms the
gap list above.
