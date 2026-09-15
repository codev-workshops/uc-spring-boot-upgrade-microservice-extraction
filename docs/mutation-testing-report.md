# Mutation testing with PIT

PIT (pitest) is wired into the Gradle build via the `info.solidsoft.pitest` plugin
with the JUnit 5 support plugin. Run it with:

```
./gradlew pitest
```

Reports are written to `build/reports/pitest/index.html` (HTML) and
`build/reports/pitest/mutations.xml` (XML). Reports are not timestamped, so each
run overwrites the previous one.

## Configuration

| Setting | Value | Why |
| --- | --- | --- |
| `mutators` | `DEFAULTS`, `REMOVE_CONDITIONALS` | Defaults cover conditional-boundary, negated-conditional, void-call and return-value patterns; `REMOVE_CONDITIONALS` stresses the authorization and null guards. |
| `targetClasses` | `io.spring.api.*`, `io.spring.application.*`, `io.spring.core.service.*` | Hand-written controller and service layers only. |
| `excludedClasses` | `io.spring.graphql.types.*`, `io.spring.graphql.DgsConstants*`, `io.spring.graphql.client.*` | DGS `generateJava` output. |
| `targetTests` | `io.spring.api.*`, `io.spring.application.*`, `io.spring.core.*` | |
| `excludedTestClasses` | `io.spring.selenium.*` | Selenium E2E tests run on TestNG via the separate `seleniumTest` task. |
| `threads` / `outputFormats` | `2` / `HTML`, `XML` | |
| `failWhenNoMutations` | `false` | Avoid spurious failures during setup. |
| `mutationThreshold` | not set | Establish a baseline before gating. |

## Scores

| | Baseline | After new tests |
| --- | ---: | ---: |
| Total mutants | 829 | 829 |
| Killed | 189 | 267 |
| Survived | 123 | 72 |
| No coverage | 517 | 490 |
| Line coverage | 81% (513/632) | 85% (539/632) |
| Mutation score (killed / total) | 23% | 32% |
| Mutation score (killed / covered) | 61% | 79% |

Per targeted class (killed / survived / no coverage):

| Class | Baseline | After |
| --- | ---: | ---: |
| `io.spring.core.service.AuthorizationService` | 5 / 2 / 0 | 7 / 0 / 0 |
| `io.spring.api.CommentsApi` | 7 / 2 / 0 | 9 / 0 / 0 |
| `io.spring.application.ProfileQueryService` | 2 / 5 / 0 | 7 / 0 / 0 |
| `io.spring.application.ArticleQueryService` | 37 / 13 / 19 | 69 / 0 / 0 |
| `io.spring.application.CursorPager` | 5 / 5 / 8 | 18 / 0 / 0 |
| `io.spring.application.CommentQueryService` | 3 / 12 / 19 | 15 / 0 / 19 |

The `ArticleApi` authorization guards (`canWriteArticle` on update/delete) were
already fully killed by the existing 403 tests in `ArticleApiTest` and are not
listed below.

## Top surviving mutants in the baseline (all now killed)

| # | Class / method | Line | Mutator | Why it survived |
| --- | --- | ---: | --- | --- |
| 1 | `AuthorizationService.canWriteComment` | 13 | `RemoveConditional_EQUAL_IF` / `EQUAL_ELSE` | Only the "article author" and "neither" paths were exercised; the second `\|\|` operand (comment author) was never the deciding one. |
| 2 | `CommentsApi.createComment` | 48 | `VoidMethodCall` (removed `commentRepository.save`) | The test only checked the 201 response; the repository is a mock so the missing save was invisible. |
| 3 | `CommentsApi.deleteComment` | 81 | `VoidMethodCall` (removed `commentRepository.remove`) | Same: 204 asserted, `remove` never verified. |
| 4 | `ProfileQueryService.findByUsername` | 20 | `RemoveConditional_EQUAL_ELSE` (`userData == null`) | No test for an unknown username. |
| 5 | `ProfileQueryService.findByUsername` | 28, 30 | `RemoveConditional` on `currentUser != null && isUserFollowing(...)` | Only one call with a non-following user; `isFollowing` never asserted true, and the null-user branch never taken. |
| 6 | `ArticleQueryService.findById` | 32, 35 | `RemoveConditional` (`articleData == null`, `user != null`) | Never called with a missing id or anonymous user. |
| 7 | `ArticleQueryService.findBySlug` | 44-50 | all (`NO_COVERAGE`) | Method not called from any unit test. |
| 8 | `ArticleQueryService.findRecentArticlesWithCursor` | 65-74 | `ConditionalsBoundary` (`size() > limit`), `RemoveConditional` (`size()==0`, `!page.isNext()`), `VoidMethodCall` (`Collections.reverse`, `fillExtraInfo`) | DB test used a single article, so the `hasExtra` boundary, empty result and PREV direction were never hit. |
| 9 | `ArticleQueryService.fillExtraInfo` / `setIsFollowingAuthor` / `setIsFavorite` | 127, 142, 169, 180 | `RemoveConditional_EQUAL_IF`, `VoidMethodCall` (`setFollowing`) | Follow/favorite flags were never asserted true for list queries. |
| 10 | `CursorPager` | 15, 33, 37 (+ `hasNext`/`hasPrevious`/`isNext`/`isPrevious` uncovered) | `RemoveConditional`, `NullReturnVals` | Direction branch and empty-data cursors never asserted directly. |
| 11 | `CommentQueryService.findByArticleId` / `findById` | 25, 30, 39, 48, 49 | `ConditionalsBoundary`, `RemoveConditional`, `VoidMethodCall` | `comments.size() > 0 && user != null` and the following flag never asserted. |

## Tests added

- `core/service/AuthorizationServiceTest` — each `||` operand independently, plus `canWriteArticle` true/false.
- `api/CommentsApiTest` — `verify(save)` with an `ArgumentCaptor`, `verify(remove)` on 204, `never().remove` on 403.
- `application/profile/ProfileQueryServiceUnitTest` — null user data, null current user, following true/false.
- `application/article/ArticleQueryServiceUnitTest` — `findById`/`findBySlug` null and anonymous branches; cursor pagination at, over and below the limit for both directions; favorite/following enrichment with and without a current user; user feed with no followed users.
- `application/CursorPagerTest` — direction handling and start/end cursors.
- `application/comment/CommentQueryServiceUnitTest` — null comment, empty list, anonymous user, following flag.

## Remaining survivors and next steps

72 mutants still survive; the largest groups are `Page` (21), `WebSecurityConfig`
(8), `UpdateUserValidator` (6), `CursorPageParameter` (5) and `CommentData` (5).
Most of the 490 `NO_COVERAGE` mutants are Lombok-generated `equals`/`hashCode`/
`toString`/setters in `io.spring.application.data.*` and `*Param` builders.

Suggested follow-ups:

- Add `lombok.config` with `lombok.addLombokGeneratedAnnotation = true` so PIT
  skips Lombok-generated code (this would remove most of the `NO_COVERAGE` noise
  and make the score meaningful).
- Cover `CommentQueryService.findByArticleIdWithCursor` (19 uncovered mutants).
- Once the score is stable, add a `mutationThreshold` gate (start at the
  killed/covered figure and ratchet up).
