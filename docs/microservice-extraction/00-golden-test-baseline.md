# Golden regression baseline (Phase 0)

The 27 test files that exist before the strangler extraction begins are the **golden baseline**: they
encode the behaviour of the monolith as it is today. Every later phase
(Favorite -> Comment -> Tag+Article -> Article -> User) must keep them passing unmodified. If a
change makes one of them fail, the change altered externally observable behaviour and needs a
conscious decision, not a test edit.

## How to run

```bash
# JDK 11 is required: the Spotless google-java-format task fails on JDK 17+
export JAVA_HOME=/path/to/jdk-11
./gradlew build -x jacocoTestCoverageVerification   # compile + all JUnit tests (per AGENTS.md)
./gradlew test -x jacocoTestCoverageVerification    # tests only
./gradlew test --tests 'io.spring.api.ArticleFavoriteApiTest'   # one class
./gradlew seleniumTest                              # TestNG/Selenium E2E, separate task, needs a running app
./gradlew contractTest                              # Spring Cloud Contract verification (see 01-contract-testing.md)
```

The JUnit `test` task excludes `io/spring/selenium/**`. The JaCoCo gate (80%) is pre-existing and
under the threshold; always skip it with `-x jacocoTestCoverageVerification` and do not "fix" it.

Test profile: `src/main/resources/application-test.properties` (note: it lives in `main/resources`),
in-memory SQLite plus `spring.flyway.target=1`, so tests run against migration `V1` of the schema.

## Layer conventions

| Base class / annotation | Purpose |
| --- | --- |
| `@MybatisTest` + `io.spring.infrastructure.DbTestBase` | mapper/repository tests against in-memory SQLite |
| `@WebMvcTest` + `io.spring.api.TestWithCurrentUser` | controller slice tests with an authenticated current user |
| `@Import(...)` + `DbTestBase` | query-service (read model) tests wiring read services and repositories |
| plain JUnit 5 + Mockito | pure domain / service unit tests |

## Baseline inventory

### Support / infrastructure (not tests themselves)

| File | Role |
| --- | --- |
| `io/spring/TestHelper.java` | fixture factory for articles/users |
| `io/spring/api/TestWithCurrentUser.java` | `@WebMvcTest` base: security config, JWT, current-user resolution |
| `io/spring/infrastructure/DbTestBase.java` | `@MybatisTest` base: SQLite + Flyway |
| `io/spring/selenium/pages/BasePage.java`, `io/spring/selenium/tests/BaseTest.java`, `io/spring/selenium/listeners/TestListener.java` | Selenium/TestNG E2E scaffolding |

### API layer (`@WebMvcTest`)

| Test class | Domain / layer | Guards phase |
| --- | --- | --- |
| `api/ArticleFavoriteApiTest` | Favorite write path, `POST`/`DELETE /articles/{slug}/favorite` | **Phase 1 (Favorite)** — the single most important baseline for the first extraction |
| `api/ArticleApiTest` | single-article read/update/delete, incl. non-author -> 403 | Phase 1 (favorite fields in the envelope), Phase 3/4 |
| `api/ArticlesApiTest` | article creation, validation, slug generation | Phase 3 (Tag+Article), Phase 4 |
| `api/ListArticleApiTest` | article feed/list, incl. `favoritedBy` filtering surface | Phase 1, Phase 4 |
| `api/CommentsApiTest` | comment create/list/delete, incl. non-author -> 403 | Phase 2 (Comment) |
| `api/ProfileApiTest` | profile read, follow/unfollow | Phase 5 (User) |
| `api/CurrentUserApiTest` | `GET/PUT /user`, JWT-authenticated current user | Phase 5 (User) |
| `api/UsersApiTest` | registration and login | Phase 5 (User) |

### Application layer (read models, `@Import` + `DbTestBase`)

| Test class | Domain / layer | Guards phase |
| --- | --- | --- |
| `application/article/ArticleQueryServiceTest` | `ArticleQueryService`: article read model incl. `favoritesCount` / `favorited` | **Phase 1 (Favorite)** and Phase 4 |
| `application/comment/CommentQueryServiceTest` | comment read model | Phase 2 (Comment) |
| `application/tag/TagsQueryServiceTest` | tag read model | Phase 3 (Tag+Article) |
| `application/profile/ProfileQueryServiceTest` | profile read model, following flag | Phase 5 (User) |

### Core domain (plain unit tests)

| Test class | Domain / layer | Guards phase |
| --- | --- | --- |
| `core/article/ArticleTest` | `Article` aggregate: slug generation, update semantics | Phase 3/4 |

### Infrastructure / persistence (`DbTestBase`)

| Test class | Domain / layer | Guards phase |
| --- | --- | --- |
| `infrastructure/favorite/MyBatisArticleFavoriteRepositoryTest` | `ArticleFavorite` persistence | **Phase 1 (Favorite)** |
| `infrastructure/comment/MyBatisCommentRepositoryTest` | comment persistence | Phase 2 (Comment) |
| `infrastructure/article/MyBatisArticleRepositoryTest` | article + tag persistence | Phase 3/4 |
| `infrastructure/article/ArticleRepositoryTransactionTest` | transactional behaviour of article writes | Phase 3/4 |
| `infrastructure/user/MyBatisUserRepositoryTest` | user persistence | Phase 5 (User) |
| `infrastructure/service/DefaultJwtServiceTest` | JWT signing/parsing — the auth contract every extracted service must honour | all phases |

### Application bootstrap and E2E

| Test class | Domain / layer | Guards phase |
| --- | --- | --- |
| `RealworldApplicationTests` | Spring context loads | all phases (smoke) |
| `selenium/tests/SeleniumSetupTest` | browser E2E smoke, run via `./gradlew seleniumTest` | all phases, not part of `build` |

## Phase 0 additions (this PR)

New tests were added alongside the baseline; no existing file was modified.

| New test file | Purpose |
| --- | --- |
| `core/service/AuthorizationServiceTest` | `canWriteArticle` / `canWriteComment` truth table |
| `api/ArticleAuthorizationApiTest` | article 401/403/404 authorization cases missing from `ArticleApiTest` |
| `api/CommentAuthorizationApiTest` | comment authorization incl. article author deleting a foreign comment |
| `infrastructure/favorite/ArticleFavoriteRepositoryEdgeCaseTest` | double favorite, raw PK violation, unfavorite no-op |
| `infrastructure/favorite/ArticleFavoritesReadServiceTest` | zero counts, unknown ids, empty and 600-id batches |
| `application/favorite/FavoriteQueryServiceTest` | zero favorites, anonymous reads, `favoritedBy` filter |
| `application/favorite/FavoriteCountContractTest` | `setFavoriteCount` null-vs-zero mapping contract |
| `api/ArticleFavoriteApiEdgeCaseTest` | 404 unknown slug, 401 anonymous, repeated favorite, JSON count |
| `harness/ParallelRunHarness`, `harness/RoutePath`, `harness/FavoriteParallelRunTest` | monolith-vs-extracted envelope comparison (see `02-parallel-run-harness.md`) |
| `contract/FavoriteContractBase` + `src/test/resources/contracts/**` | consumer-driven contract scaffolding (see `01-contract-testing.md`) |

## Contracts later phases must preserve

Behaviours discovered while writing the tests above. They are asserted, so they are now regressions
if changed:

1. **Favoriting is idempotent at repository level, not at mapper level.**
   `MyBatisArticleFavoriteRepository.save` does a `find` first and only inserts when absent, so
   favoriting twice is a no-op. Calling `ArticleFavoriteMapper.insert` directly with the same
   `(article_id, user_id)` fails the `article_favorites` primary key and surfaces as
   `org.springframework.jdbc.UncategorizedSQLException` ("UNIQUE constraint failed"), *not* as
   `DataIntegrityViolationException`. An extracted Favorite service must keep the read-then-insert
   semantics (or translate the PK violation) to preserve the current 200 response on a repeated
   `POST /articles/{slug}/favorite`.
2. **`favoritesCount` is `0`, never `null`, for an existing article.**
   `ArticleFavoritesReadService.articlesFavoriteCount` uses a `LEFT JOIN`, so an existing article
   with no favorites returns a row with count `0`; `ArticleData.favoritesCount` is a primitive
   `int`, so the JSON is `"favoritesCount": 0`. Unknown article ids produce **no** row at all, and
   `ArticleQueryService.setFavoriteCount` would then dereference a missing map entry
   (`NullPointerException`). Any replacement of the count query must keep returning a zero row for
   every requested existing id.
3. **Unfavoriting something that was never favorited is a no-op returning 200**, because
   `ArticleFavoriteApi` deletes only when `find` returns a row.
4. **A missing article slug is a 404 on both favorite and unfavorite**
   (`ResourceNotFoundException`), i.e. slug resolution happens before any favorite mutation.
5. **Anonymous reads report `favorited=false`.** `setIsFavorite` is skipped when
   `currentUser == null`; the single-article anonymous path skips `fillExtraInfo` entirely.
6. **`articlesFavoriteCount` / `userFavorites` must not be called with an empty id list** — the
   generated `IN ()` SQL fails. Callers guard this today; extracted services must keep guarding it.
   Large batches (600 ids, above SQLite's default variable limit) work.
7. **Authorization is ownership-based:** an article author may write their own article and may
   delete any comment on their article; a comment author may write their own comment; anyone else
   gets `403`. Anonymous gets `401`. Unknown slug/comment gets `404` (checked before authorization).
