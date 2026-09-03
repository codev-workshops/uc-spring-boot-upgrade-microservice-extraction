# comment-service

Phase 2 of the microservice extraction (`docs/microservice-extraction/phases/phase-2-comment.md`):
the Comment domain of the Conduit monolith as a standalone Spring Boot 2.6.3 / Java 11 service.
It owns exactly one table, `comments`, in its own SQLite file and exposes the internal REST API the
monolith's `RemoteComment*` adapters call. It never reads the monolith's `dev.db`. It returns raw
comment rows only (`id, body, articleId, userId, createdAt, updatedAt`); author profiles and the
"comment author OR article author" delete rule stay in the monolith.

This directory is an **independent Gradle build** (own `settings.gradle` and wrapper). The root
build does not include it and must never `include 'comment-service'`; the root Spotless block
excludes `comment-service/**`.

## Build, test, run

Spotless (google-java-format) needs **JDK 11**; export `JAVA_HOME` first.

```bash
export JAVA_HOME=/path/to/jdk11
cd comment-service

./gradlew build              # compile + unit/@WebMvcTest/@MybatisTest + contract verification + spotless + jacoco
./gradlew test               # unit tests only
./gradlew contractTest       # provider-side Spring Cloud Contract verification (MOCKMVC mode)
./gradlew spotlessApply      # format before committing
./gradlew bootRun            # :8082, deletes and recreates ./comment.db (Flyway V1 + V2 seed)
# or, after build:
java -jar build/libs/comment-service-0.0.1-SNAPSHOT.jar
```

| Item | Value |
|---|---|
| Port | `8082` (`server.port`, override with `SERVER_PORT`) |
| Database | `jdbc:sqlite:comment.db` in the working directory (git-ignored) |
| Migrations | `src/main/resources/db/migration/V1__create_comment_tables.sql` (table, identical DDL to the monolith, no FKs), `V2__seed_comments.sql` (the 5 comment rows from the monolith's `V2__seed_data.sql`) |
| Test profile | `application-test.properties`: `jdbc:sqlite::memory:`, `spring.flyway.target=1` (no seed) |
| Health | `GET /actuator/health` -> `{"status":"UP"}` |

### Properties (`src/main/resources/application.properties`)

| Property | Default | Notes |
|---|---|---|
| `server.port` | `8082` | |
| `spring.datasource.url` | `jdbc:sqlite:comment.db` | own file, never `dev.db` |
| `jwt.secret` | same value as the monolith | HS512; tokens issued by the monolith's `DefaultJwtService` validate here |
| `jwt.sessionTime` | `86400` | only used when the service mints tokens in tests |
| `management.endpoints.web.exposure.include` | `health` | |

`spring.jackson.deserialization.UNWRAP_ROOT_VALUE` is **not** enabled: the internal POST body is
not root-wrapped.

## Internal REST API (canonical contract, phase-2-comment.md §2.1)

All responses are JSON. Errors use the monolith envelope `{"errors":{"body":["..."]}}`:
`422` blank body / invalid request, `401` missing or invalid token, `403` token subject != `userId`,
`404` unknown comment.

Timestamps are rendered exactly like the monolith (`ISODateTimeFormat.dateTime().withZoneUTC()`),
e.g. `2024-01-31T10:15:30.123Z`; `updatedAt` always equals `createdAt` (the monolith's
`TransferData.xml` quirk). Cursor values are epoch millis.

| Method / path | Request | Response | Auth |
|---|---|---|---|
| `GET /internal/articles/{articleId}/comments` | — | `200 {"comments":[row...]}` ordered `created_at DESC` | none |
| `GET /internal/articles/{articleId}/comments/cursor?limit=20&direction=next\|prev[&cursor=<millis>]` | — | `200 {"comments":[row...]}` — up to `limit+1` rows; `next`: `created_at < cursor`, DESC; `prev`: `created_at > cursor`, ASC (the monolith reverses); no cursor = from the edge | none |
| `GET /internal/comments/{id}[?articleId=...]` | — | `200 {"comment":row}`; `404` when unknown or not under `articleId` | none |
| `POST /internal/articles/{articleId}/comments` | `{"id":"<uuid>","body":"...","userId":"u","createdAt":"2024-01-31T10:15:30.123Z"}` | `201 {"comment":row}` on insert; `200 {"comment":<stored row>}` when `id` already exists (idempotent, `insert or ignore`) | `Authorization: Token <jwt>`, JWT subject must equal `userId` |
| `DELETE /internal/articles/{articleId}/comments/{id}` | — | `204`; idempotent no-op when absent | `Authorization: Token <jwt>` (any valid user; author check is the monolith's) |
| `GET /actuator/health` | — | `200 {"status":"UP"}` | none |

Example (token from `POST http://localhost:8080/users/login` on the monolith):

```bash
curl -s http://localhost:8082/internal/articles/article-1/comments
# {"comments":[{"id":"comment-2","body":"...","articleId":"article-1","userId":"user-3","createdAt":"...","updatedAt":"..."},...]}

curl -s "http://localhost:8082/internal/articles/article-1/comments/cursor?limit=1&direction=next"

curl -s -X POST -H 'Content-Type: application/json' -H "Authorization: Token $JWT" \
  -d '{"id":"c-1","body":"hello","userId":"user-1","createdAt":"2024-01-31T10:15:30.123Z"}' \
  http://localhost:8082/internal/articles/article-1/comments
# 201 {"comment":{"id":"c-1","body":"hello","articleId":"article-1","userId":"user-1","createdAt":"2024-01-31T10:15:30.123Z","updatedAt":"2024-01-31T10:15:30.123Z"}}

curl -s http://localhost:8082/internal/comments/c-1?articleId=article-1

curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "Authorization: Token $JWT" \
  http://localhost:8082/internal/articles/article-1/comments/c-1
# 204
```

## Layout

```
src/main/java/io/spring/comment/
  CommentServiceApplication.java, JacksonCustomizations.java (monolith DateTime serializer)
  api/            CommentInternalApi, NewCommentRequest, exception/ (error envelope), security/ (JwtTokenFilter, WebSecurityConfig)
  core/           Comment, CommentRepository, JwtService
  application/    CommentQueryService, CommentCommandService, CursorPageParameter, data/ CommentData
  infrastructure/ MyBatis mapper + read service, DateTimeHandler, MyBatisCommentRepository, DefaultJwtService
src/main/resources/mapper/*.xml           MyBatis XML mappers
src/test/java/io/spring/comment/          @MybatisTest, @WebMvcTest, @SpringBootTest, contract base class
src/test/resources/contracts/comment/     Spring Cloud Contract DSL for every internal endpoint (+ 401/403/404/422 cases)
```

The contracts are verified against `CommentInternalApi` with standalone MockMvc
(`CommentInternalApiContractBase`); the same files produce the WireMock stubs the monolith's
consumer tests run against (`./gradlew publishToMavenLocal` publishes `comment-service-0.0.1-SNAPSHOT-stubs.jar`).
