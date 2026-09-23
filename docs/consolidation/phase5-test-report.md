# Phase 5 Test Report — Final regression and sign-off

## Scope

Final end-to-end regression after the GraphQL layer was removed in Phase 4: unit, service,
infrastructure, controller and Selenium tests, plus a repository-wide scan for GraphQL/DGS remnants.
No code changes in this phase.

## Commands and outcomes

| Command | Outcome |
|---|---|
| `./gradlew clean build -x jacocoTestCoverageVerification --console=plain` | PASS — **88 total, 88 passed, 0 failed, 0 skipped** (JUnit 5 task `test`) |
| `DISPLAY=:0 ./gradlew seleniumTest --console=plain` | PASS — **2 total, 2 passed, 0 failed, 0 skipped** (TestNG suite `src/test/resources/selenium/testng-smoke.xml`) |
| `grep -rniE "graphql\|graphiql\|netflix\|dgs" src build.gradle settings.gradle README.md AGENTS.md frontend/lib frontend/pages frontend/components` | No matches in main or test sources, build files or docs. The only hit is a `dgs` byte sequence inside a base64-encoded PNG literal in `frontend/lib/utils/constant.ts` (not a reference). |

Combined: **90 tests, 90 passed, 0 failed, 0 skipped.** The global JaCoCo threshold is skipped per
`AGENTS.md` (pre-existing gate, out of scope).

### Breakdown of the 88 JUnit tests by package

| Package | Tests |
|---|---|
| `io.spring` (Spring context `contextLoads`) | 1 |
| `io.spring.api` (REST controllers, 11 classes) | 55 |
| `io.spring.application.article` | 9 |
| `io.spring.application.comment` | 2 |
| `io.spring.application.profile` | 1 |
| `io.spring.application.tag` | 1 |
| `io.spring.core.article` | 5 |
| `io.spring.infrastructure.article` | 4 |
| `io.spring.infrastructure.comment` | 1 |
| `io.spring.infrastructure.favorite` | 2 |
| `io.spring.infrastructure.service` | 3 |
| `io.spring.infrastructure.user` | 4 |

Note: `seleniumTest` is a separate Gradle task (the default `test` task excludes
`io/spring/selenium/**`). The Selenium suite currently contains WebDriver setup smoke tests
(`SeleniumSetupTest.testBrowserLaunches`, `testWebDriverManagerSetup`); it does not exercise the
GraphQL or REST API and was unaffected by the consolidation.

## Functionality → test → result (final)

| Functionality | Test class / method | Result |
|---|---|---|
| App context boots (REST only, no DGS) | `RealworldApplicationTests.contextLoads` | PASS |
| Articles: create / read / update / delete, 403/404/422 paths | `ArticlesApiTest` (3), `ArticleApiTest` (6) | PASS |
| Articles list & feed — offset paging | `ListArticleApiTest` (3), `CursorArticlesApiTest.should_keep_offset_paging_when_no_cursor_params` | PASS |
| Articles list & feed — cursor paging (`first/after`, `last/before`) | `CursorArticlesApiTest` (8) | PASS |
| Favorite / unfavorite | `ArticleFavoriteApiTest` (2) | PASS |
| Comments: add / list / delete, cursor paging | `CommentsApiTest` (5), `CursorCommentsApiTest` (5) | PASS |
| Users: register / login, 422 `errors` map contract (request- and service-level) | `UsersApiTest` (9) | PASS |
| Current user: me / update | `CurrentUserApiTest` (6) | PASS |
| Profiles: get / follow / unfollow, 401/404 | `ProfileApiTest` (6) | PASS |
| Tags | `TagsApiTest` (2) | PASS |
| Service, domain and infrastructure layers | 32 tests across `io.spring.application.*`, `io.spring.core.*`, `io.spring.infrastructure.*` | PASS |
| Selenium/WebDriver smoke | `SeleniumSetupTest` (2) | PASS |

## Regressions

None found across all five phases. Test count progression: 68 (Phase 1 baseline) → 81 (Phase 2) →
88 (Phase 3) → 88 (Phase 4, GraphQL had no dedicated tests) → 88 + 2 Selenium (Phase 5).

## Sign-off

All functionality previously exposed through the Netflix DGS GraphQL layer (`io.spring.graphql`,
`schema.graphqls`, generated `io.spring.graphql.types`) is now served exclusively by the REST layer in
`io.spring.api`:

- every GraphQL query, mutation and field resolver maps to a tested REST endpoint
  (`phase3-test-report.md`);
- Relay-style cursor pagination is available on `GET /articles`, `GET /articles/feed` and
  `GET /articles/{slug}/comments` via `first/after` and `last/before`, returning
  `startCursor`/`endCursor`/`hasNext`/`hasPrevious` (`phase2-test-report.md`);
- the GraphQL `UserResult` union error contract is replaced by the REST 422 `errors` map produced by
  `CustomizeExceptionHandler` (`phase3-test-report.md`);
- no GraphQL, GraphiQL or DGS references remain in main or test sources, build configuration or
  documentation (`phase4-test-report.md`, this report).

**Phase complete. Consolidation signed off — the backend is REST-only.**
