# favorite-service

Phase 1 of the microservice extraction (`docs/microservice-extraction/phases/phase-1-favorite.md`):
the Favorite domain of the Conduit monolith as a standalone Spring Boot 2.6.3 / Java 11 service.
It owns exactly one table, `article_favorites`, in its own SQLite file and exposes the internal
REST API the monolith's `RemoteFavorite*` adapters call. It never reads the monolith's `dev.db`.

This directory is an **independent Gradle build** (own `settings.gradle` and wrapper). The root
build does not include it and must never `include 'favorite-service'`.

Note: the root build's Spotless target is `**/*.java`, so it also checks `favorite-service/src` (keep
it google-java-format clean) and, after a service build, the generated contract tests under
`favorite-service/build/`. Run `(cd favorite-service && ./gradlew clean)` before the root
`./gradlew build`, or exclude `favorite-service/**` from the root Spotless target.

## Build, test, run

Spotless (google-java-format) needs **JDK 11**; export `JAVA_HOME` first.

```bash
export JAVA_HOME=/path/to/jdk11
cd favorite-service

./gradlew build              # compile + unit/@WebMvcTest/@MybatisTest + contract verification + spotless + jacoco
./gradlew test               # unit tests only
./gradlew contractTest       # provider-side Spring Cloud Contract verification (MOCKMVC mode)
./gradlew spotlessApply      # format before committing
./gradlew bootRun            # :8081, deletes and recreates ./favorite.db (Flyway V1 + V2 seed)
# or, after build:
java -jar build/libs/favorite-service-0.0.1-SNAPSHOT.jar
```

| Item | Value |
|---|---|
| Port | `8081` (`server.port`, override with `SERVER_PORT`) |
| Database | `jdbc:sqlite:favorite.db` in the working directory (git-ignored) |
| Migrations | `src/main/resources/db/migration/V1__create_favorite_tables.sql` (table), `V2__seed_favorites.sql` (the 6 seed rows from the monolith's `V2__seed_data.sql`) |
| Test profile | `application-test.properties`: `jdbc:sqlite::memory:`, `spring.flyway.target=1` (no seed) |
| Health | `GET /actuator/health` -> `{"status":"UP"}` |

### Properties (`src/main/resources/application.properties`)

| Property | Default | Notes |
|---|---|---|
| `server.port` | `8081` | |
| `spring.datasource.url` | `jdbc:sqlite:favorite.db` | own file, never `dev.db` |
| `jwt.secret` | same value as the monolith | HS512; tokens issued by the monolith's `DefaultJwtService` validate here |
| `jwt.sessionTime` | `86400` | only used when the service mints tokens in tests |
| `favorite.max-batch-size` | `500` | id batches larger than this are rejected with 422 |
| `management.endpoints.web.exposure.include` | `health` | |

Unlike the monolith, `spring.jackson.deserialization.UNWRAP_ROOT_VALUE` is **not** enabled: the
internal request bodies in the contract (`{"articleIds":[...]}`) are not root-wrapped.

## Internal REST API (canonical contract, phase-1-favorite.md §2.1)

All responses are JSON. Errors use the monolith envelope `{"errors":{"body":["..."]}}`:
`422` invalid body / batch too large, `401` missing or invalid token, `403` token subject != `userId`.

| Method / path | Request | Response | Auth |
|---|---|---|---|
| `POST /internal/favorites/counts` | `{"articleIds":["a","b"]}` | `200 {"counts":[{"articleId":"a","count":2},{"articleId":"b","count":0}]}` — exactly one entry per requested id, in request order, `0` when none; empty list -> `{"counts":[]}`; more than 500 ids -> `422` | none |
| `POST /internal/favorites/query` | `{"userId":"u","articleIds":["a","b"]}` | `200 {"userId":"u","articleIds":["a"]}` — the favorited subset, in request order; more than 500 ids -> `422` | none |
| `GET /internal/favorites/by-user/{userId}/article-ids` | — | `200 {"userId":"u","articleIds":[...]}` sorted by article id (for `favoritedBy`) | none |
| `PUT /internal/favorites/{articleId}/{userId}` | — | `200 {"articleId":"a","userId":"u","favorited":true}`; idempotent (`insert or ignore`) | `Authorization: Token <jwt>`, JWT subject must equal `{userId}` |
| `DELETE /internal/favorites/{articleId}/{userId}` | — | `204`; idempotent no-op when absent | same as PUT |
| `GET /actuator/health` | — | `200 {"status":"UP"}` | none |

Example (token from `POST http://localhost:8080/users/login` on the monolith):

```bash
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"articleIds":["article-1","article-9"]}' http://localhost:8081/internal/favorites/counts
# {"counts":[{"articleId":"article-1","count":2},{"articleId":"article-9","count":0}]}

curl -s -X PUT -H "Authorization: Token $JWT" http://localhost:8081/internal/favorites/article-9/user-1
# {"articleId":"article-9","userId":"user-1","favorited":true}
```

## Layout

```
src/main/java/io/spring/favorite/
  FavoriteServiceApplication.java
  api/            FavoriteInternalApi, request bodies, exception/ (error envelope), security/ (JwtTokenFilter, WebSecurityConfig)
  core/           ArticleFavorite, ArticleFavoriteRepository, JwtService
  application/    FavoriteQueryService (0-fill, ordering, empty short-circuit), FavoriteCommandService, data/ DTOs
  infrastructure/ MyBatis mapper + read service, MyBatisArticleFavoriteRepository, DefaultJwtService
src/main/resources/mapper/*.xml           MyBatis XML mappers
src/test/java/io/spring/favorite/         @MybatisTest, @WebMvcTest, @SpringBootTest, contract base class
src/test/resources/contracts/favorite/    Spring Cloud Contract DSL for the five internal endpoints (+ 403 case)
```

The contracts are verified against `FavoriteInternalApi` with standalone MockMvc
(`FavoriteInternalApiContractBase`); the same files produce the WireMock stubs the monolith's
consumer tests run against (`./gradlew publishToMavenLocal` publishes `favorite-service-0.0.1-SNAPSHOT-stubs.jar`).
