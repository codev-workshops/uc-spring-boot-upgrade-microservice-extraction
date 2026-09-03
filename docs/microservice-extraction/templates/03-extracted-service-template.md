# 03 — Extracted Service Template

Reusable, step-by-step checklist for carving a `<domain>-service/` out of the Conduit monolith
per [AGENTS.md](../../../AGENTS.md). This document is Phase 0 scaffolding only: it describes
*how* to build an extracted service; it does **not** create one and nothing under `src/` of the
monolith changes. Concrete file templates live in [`skeleton/`](skeleton/) as `*.template` files
(with `{{domain}}` placeholders) so they are never compiled by the root Gradle build.

Migration order for the later phases: **Favorite → Comment → Tag (bundled with Article) →
Article → User**. Examples below use `favorite` (Phase 1) but every step is domain-agnostic.

---

## 0. Placeholders

| Placeholder    | Meaning                                    | Favorite example         |
|----------------|--------------------------------------------|--------------------------|
| `{{domain}}`   | lower-case domain, used in paths/packages  | `favorite`               |
| `{{Domain}}`   | PascalCase domain, used in class names     | `Favorite`               |
| `{{port}}`     | HTTP port of the new service (see §11)     | `8081`                   |
| `{{Other}}`    | PascalCase name of a domain the service must call over REST | `Article`, `User` |
| `{{other}}`    | lower-case form of `{{Other}}`             | `article`, `user`        |
| `{{tables}}`   | tables that move with the domain (see §9)  | `article_favorites`      |

Copy `skeleton/` into `<domain>-service/`, strip the `.template` suffix, and substitute:

```bash
DOMAIN=favorite; Domain=Favorite; PORT=8081
mkdir -p ${DOMAIN}-service
cp -r docs/microservice-extraction/templates/skeleton/. ${DOMAIN}-service/
cd ${DOMAIN}-service
find . -name '*.template' | while read f; do mv "$f" "${f%.template}"; done
# rename placeholder path segments and file names
find . -depth -name '*{{domain}}*' | while read f; do mv "$f" "${f//\{\{domain\}\}/$DOMAIN}"; done
find . -depth -name '*{{Domain}}*' | while read f; do mv "$f" "${f//\{\{Domain\}\}/$Domain}"; done
grep -rl '{{' . | xargs sed -i "s/{{domain}}/$DOMAIN/g; s/{{Domain}}/$Domain/g; s/{{port}}/$PORT/g"
```

`{{Other}}`/`{{other}}` are substituted by hand because a service may need more than one client.

---

## 1. Project layout (standalone Gradle project)

```
<domain>-service/
├── build.gradle                 # independent; NOT included by the root project
├── settings.gradle              # rootProject.name = '<domain>-service'
├── gradlew, gradlew.bat, gradle/wrapper/   # copied from the monolith (same Gradle version)
└── src/
    ├── main/java/io/spring/<domain>/
    │   ├── <Domain>ServiceApplication.java
    │   ├── api/                 # REST controllers, security, exception handling
    │   │   ├── exception/       # copy of io.spring.api.exception (same error envelope)
    │   │   └── security/        # JwtTokenFilter + WebSecurityConfig (validation only)
    │   ├── core/                # domain entities + repository interfaces
    │   ├── application/         # query services, DTOs, REST clients
    │   │   ├── dto/             # cross-service DTOs (owned by THIS service)
    │   │   └── client/          # <Other>ServiceClient classes
    │   └── infrastructure/
    │       ├── mybatis/mapper/  # @Mapper interfaces
    │       ├── mybatis/readservice/
    │       ├── repository/      # MyBatis*Repository (@Repository)
    │       └── service/         # DefaultJwtService (validation only)
    ├── main/resources/
    │   ├── application.properties
    │   ├── application-test.properties       # mirrors the monolith: it lives in main/resources
    │   ├── db/migration/V1__create_<domain>_tables.sql
    │   ├── db/migration/V2__seed_<domain>_data.sql   # optional
    │   └── mapper/*.xml
    └── test/java/io/spring/<domain>/...
```

The package root is `io.spring.<domain>` so that the four AGENTS.md layers
(`api/`, `core/`, `application/`, `infrastructure/`) are preserved verbatim beneath it.

**Root-build isolation rule.** The monolith has no `settings.gradle` (it is a single-project
build) so a nested `<domain>-service/` directory with its own `settings.gradle` is a separate
Gradle build — the monolith never sees it. Never add `include '<domain>-service'` to the root, and
never reference monolith source sets from the service. Both builds must pass on their own.

---

## 2. `build.gradle` — same versions as the monolith

Copy versions exactly from the monolith's `build.gradle` (do not bump anything in Phase 0/1):

| Coordinate                                                  | Version           |
|-------------------------------------------------------------|-------------------|
| `org.springframework.boot` plugin                           | 2.6.3             |
| `io.spring.dependency-management` plugin                    | 1.0.11.RELEASE    |
| `com.diffplug.spotless` plugin                              | 6.2.1             |
| `jacoco` (toolVersion)                                      | 0.8.7             |
| `org.mybatis.spring.boot:mybatis-spring-boot-starter`       | 2.2.2             |
| `org.mybatis.spring.boot:mybatis-spring-boot-starter-test`  | 2.2.2             |
| `org.xerial:sqlite-jdbc`                                    | 3.36.0.3          |
| `org.flywaydb:flyway-core`                                  | managed by Boot   |
| `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson`   | 0.11.2            |
| `joda-time:joda-time`                                       | 2.10.13           |
| `org.projectlombok:lombok`                                  | managed by Boot   |
| `io.rest-assured:*` (test)                                  | 4.5.1             |
| `org.mockito:mockito-inline` (test)                         | 4.0.0             |
| `sourceCompatibility` / `targetCompatibility`               | 11                |

Omitted on purpose: `graphql-dgs-spring-boot-starter` and the DGS codegen plugin (GraphQL stays in
the monolith until a later decision), `spring-boot-starter-hateoas` (only needed if the service
returns HAL links), and all Selenium/TestNG deps (E2E stays in the monolith).

Add `org.springframework.boot:spring-boot-starter-actuator` for the health endpoint (§8). It is
managed by the Boot BOM, so no explicit version is needed.

Keep the same `spotless { java { googleJavaFormat() } }` block and the same JaCoCo 0.80 rule; per
AGENTS.md the gate is skipped with `-x jacocoTestCoverageVerification`, never edited.

Template: [`skeleton/build.gradle.template`](skeleton/build.gradle.template).

---

## 3. `settings.gradle` and wrapper reuse

- `settings.gradle`: `rootProject.name = '{{domain}}-service'` — template in
  [`skeleton/settings.gradle.template`](skeleton/settings.gradle.template).
- Wrapper: copy `gradlew`, `gradlew.bat` and `gradle/wrapper/` from the monolith root so both
  builds pin the identical Gradle distribution (`gradle/wrapper/gradle-wrapper.properties`).
  Do not run `gradle wrapper` with a different version.
- Toolchain: both builds must run on **JDK 11** (`.java-version` = 11). Spotless
  6.2.1 / google-java-format fails on JDK 17+; export `JAVA_HOME` to a JDK 11 before invoking
  either `./gradlew`.

---

## 4. Datasource and profiles

`application.properties` (template: [`skeleton/application.properties.template`](skeleton/application.properties.template)):

```properties
server.port={{port}}
spring.datasource.url=jdbc:sqlite:{{domain}}.db      # own file, never dev.db
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.jackson.deserialization.UNWRAP_ROOT_VALUE=true
jwt.secret=<same value as the monolith>              # tokens issued by the monolith must validate
jwt.sessionTime=86400
mybatis.configuration.map-underscore-to-camel-case=true
mybatis.type-handlers-package=io.spring.{{domain}}.infrastructure.mybatis
mybatis.mapper-locations=mapper/*.xml
{{other}}.service.url=http://localhost:8080          # monolith until {{Other}} is extracted
```

`application-test.properties` — mirror the monolith exactly (in-memory SQLite, only V1):

```properties
spring.datasource.url=jdbc:sqlite::memory:
spring.flyway.target=1
```

Like the monolith, keep it in `src/main/resources/` (not `src/test/resources/`) so
`@ActiveProfiles("test")` resolves it the same way in both projects. Add `{{domain}}.db` to the
service's `.gitignore` (the monolith already ignores `*.db`).

---

## 5. Flyway migrations — copy only what moves

- `src/main/resources/db/migration/V1__create_{{domain}}_tables.sql` containing **only** the
  tables listed for the domain in §9, copied verbatim from the monolith's
  `V1__create_tables.sql` (same column types, same primary keys). The monolith schema has **no
  foreign keys**, which is what makes table-by-table extraction possible without DDL changes.
- `V2__seed_{{domain}}_data.sql` only if the domain has seed rows in the monolith's
  `V2__seed_data.sql`; copy just those `INSERT`s. The test profile's `spring.flyway.target=1` keeps
  seeds out of tests, exactly as in the monolith.
- Never point Flyway at the monolith's `dev.db`; the two schema-history tables must live in
  different files.

Template: [`skeleton/V1__create_{{domain}}_tables.sql.template`](skeleton/V1__create_%7B%7Bdomain%7D%7D_tables.sql.template).

---

## 6. Persistence conventions (MyBatis XML + `@Repository`)

Mirror `MyBatisArticleFavoriteRepository` / `ArticleFavoriteMapper.xml`:

- `core/<domain>/<Entity>.java` — Lombok `@Getter @NoArgsConstructor @EqualsAndHashCode` entity
  and a `<Entity>Repository` interface in `core/`.
- `infrastructure/mybatis/mapper/<Entity>Mapper.java` — `@Mapper` interface with `@Param` names.
- `src/main/resources/mapper/<Entity>Mapper.xml` — `namespace` equals the mapper FQCN; use
  explicit `resultMap`s with aliased columns as the monolith does.
- `infrastructure/repository/MyBatis<Entity>Repository.java` — `@Repository`, constructor
  injection, implements the `core` interface.
- Read side (CQRS): `infrastructure/mybatis/readservice/<Entity>ReadService.java` +
  `mapper/<Entity>ReadService.xml`, consumed by an `application/<Entity>QueryService` (`@Service`).

Templates: [`skeleton/{{Domain}}Mapper.java.template`](skeleton/%7B%7BDomain%7D%7DMapper.java.template),
[`skeleton/{{Domain}}Mapper.xml.template`](skeleton/%7B%7BDomain%7D%7DMapper.xml.template),
[`skeleton/MyBatis{{Domain}}Repository.java.template`](skeleton/MyBatis%7B%7BDomain%7D%7DRepository.java.template).

---

## 7. Cross-service REST client + DTOs

Any lookup that used to be a MyBatis join into another domain's table becomes a REST call:

- `application/client/{{Other}}ServiceClient.java` — `@Service`, constructor-injected
  `RestTemplate`, base URL from `${{{other}}.service.url}`, forwards the caller's
  `Authorization: Token <jwt>` header when the downstream endpoint needs it.
- `application/dto/{{Other}}Dto.java` — the *service's own* projection of the remote payload
  (`@JsonIgnoreProperties(ignoreUnknown = true)`, `@JsonRootName("{{other}}")` because the monolith
  wraps responses). DTOs are never shared through a common module (AGENTS.md).
- Graceful failure (AGENTS.md): catch `RestClientException`, log, and either return a sensible
  default (`Optional.empty()`, `0`, empty list) or throw a domain-specific exception with a
  clear message (e.g. `{{Other}}ServiceUnavailableException`). Never let a raw `RestTemplate`
  exception surface as a 500.
- Timeouts: configure connect/read timeouts on the `RestTemplate` (`RestTemplateBuilder`), e.g.
  2 s / 5 s, so a dead dependency fails fast.

Template: [`skeleton/{{Other}}ServiceClient.java.template`](skeleton/%7B%7BOther%7D%7DServiceClient.java.template),
[`skeleton/{{Other}}Dto.java.template`](skeleton/%7B%7BOther%7D%7DDto.java.template).

---

## 8. API conventions

**JWT validation reuse.** Copy `DefaultJwtService`, `JwtTokenFilter`, and `WebSecurityConfig`
into `io.spring.{{domain}}.infrastructure.service` / `.api.security`. Keep HS512 and the *same
`jwt.secret`*; the service only needs `getSubFromToken`. Because the service has no `users`
table, the filter authenticates a lightweight principal (user id from the `sub` claim) instead of
loading a `User` from `UserRepository`; controllers take `@AuthenticationPrincipal String userId`
or a small `CurrentUser` value object. Anonymous-readable endpoints (`GET`) are `permitAll()`,
everything else `authenticated()`, matching `WebSecurityConfig`.

**Response envelope.** Every response body is wrapped in a root key, e.g.
`{"article": {...}}`, `{"comment": {...}}`, `{"comments": [...]}`, `{"profile": {...}}` — return
a `HashMap<String,Object>` / `Map.of(...)` as the monolith controllers do. Requests are unwrapped
with `spring.jackson.deserialization.UNWRAP_ROOT_VALUE=true` + `@JsonRootName` on request params.

**Error format.** Copy `io.spring.api.exception` (`CustomizeExceptionHandler`, `ErrorResource`,
`ErrorResourceSerializer`, `FieldErrorResource`, `InvalidRequestException`,
`InvalidAuthenticationException`, `NoAuthorizationException`, `ResourceNotFoundException`) so
clients keep receiving `422 {"errors": {"field": ["message"]}}`, `404`, `401`, `403` with identical
shapes.

**URL patterns.** Keep the RealWorld paths unchanged (`/articles/{slug}/favorite`,
`/articles/{slug}/comments`, `/tags`, `/profiles/{username}`, ...) so the front end / API gateway can
route by prefix without rewriting.

**Health.** `spring-boot-starter-actuator` with
`management.endpoints.web.exposure.include=health` → `GET /actuator/health` returns
`{"status":"UP"}`; `permitAll()` it in `WebSecurityConfig`. Extend with a custom `HealthIndicator`
that pings the `{{Other}}ServiceClient` if desired.

---

## 9. Table & seed inventory per domain

| Phase | Domain (service)                 | Tables that move                    | Seed rows to copy from `V2__seed_data.sql`      | Cross-domain lookups that become REST calls                                                                                                 |
|-------|----------------------------------|-------------------------------------|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| 1     | Favorite (`favorite-service`)    | `article_favorites`                 | `INSERT INTO article_favorites ...` (if present) | slug → article id (`GET /articles/{slug}`); `ArticleQueryService.setFavoriteCount/setIsFavorite` in the monolith call `GET /articles/{id}/favorites/count` and `.../favorited?userId=` on the service; current user is taken from the JWT `sub` |
| 2     | Comment (`comment-service`)      | `comments`                          | `INSERT INTO comments ...`                       | slug → article id (`GET /articles/{slug}`); author profile (`GET /profiles/{username}` or `GET /users/{id}`) incl. `following` flag           |
| 3     | Tag + Article (`article-service`)| `articles`, `tags`, `article_tags`  | `INSERT INTO tags ...`, `articles`, `article_tags` | author profile and follow relation from User; favourite counts / flags from Favorite; comments stay in Comment                              |
| 4     | User (`user-service`)            | `users`, `follows`                  | `INSERT INTO users ...`, `follows`               | none outbound; every other service calls it for profiles and `following`                                                                    |

Notes:
- The absence of foreign keys means tables can be moved without dropping constraints, but referential
  integrity is now the caller's job: validate ids via the owning service before inserting.
- Row ids are `varchar(255)` UUID-like strings and stay stable across the move; a one-off
  `sqlite3 dev.db ".dump <table>" | sqlite3 <domain>.db` copies live data at cut-over.
- `TransferData.xml` in the monolith is a read-only helper used by tests; it is not a domain and
  does not move.

---

## 10. Port allocation

| Service            | Port |
|--------------------|------|
| monolith           | 8080 |
| favorite-service   | 8081 |
| comment-service    | 8082 |
| article-service    | 8083 (tags bundled) |
| user-service       | 8084 |
| reserved           | 8085 |

Set via `server.port` in each `application.properties`; override with `SERVER_PORT` when needed.

---

## 11. Running both builds independently

```bash
export JAVA_HOME=/path/to/jdk11

# monolith (unchanged)
./gradlew build -x jacocoTestCoverageVerification
./gradlew bootRun                            # :8080, dev.db

# extracted service
cd favorite-service
./gradlew build -x jacocoTestCoverageVerification
./gradlew bootRun                            # :8081, favorite.db
```

Local dev order: start the monolith first (the service's `{{other}}.service.url` points at it),
then the service. There is no shared Gradle daemon state or DB file between the two.

---

## 12. Template verification checklist

- [ ] `./gradlew build -x jacocoTestCoverageVerification` passes in the **monolith** (JDK 11).
- [ ] `./gradlew build -x jacocoTestCoverageVerification` passes in **`<domain>-service/`** (JDK 11).
- [ ] `./gradlew spotlessCheck` passes in both.
- [ ] Root build does not compile anything under `<domain>-service/` (`./gradlew projects` in
      the root lists only the root project; no `.template` file is ever renamed inside `docs/`).
- [ ] Fresh start: delete `<domain>.db`, run `bootRun`, confirm Flyway applies `V1` (and `V2` if
      present) — log line `Successfully applied 1 migration` — and `sqlite3 <domain>.db .tables`
      lists only `{{tables}}` + `flyway_schema_history`.
- [ ] Test profile: a `@MybatisTest`-style test with `@ActiveProfiles("test")` runs against
      `jdbc:sqlite::memory:` with `spring.flyway.target=1`.
- [ ] No shared DB path: `grep -r "dev.db" <domain>-service/` returns nothing; the monolith's
      `application.properties` is untouched.
- [ ] `GET http://localhost:{{port}}/actuator/health` → `{"status":"UP"}`.
- [ ] A JWT from `POST http://localhost:8080/users/login` is accepted by the service
      (`Authorization: Token <jwt>`).
- [ ] With the monolith stopped, the service's endpoints that depend on `{{Other}}ServiceClient`
      return the documented default or domain error — not a stack trace.
- [ ] No production file under the monolith's `src/main` changed (`git diff --stat main -- src/`).

---

## Open questions (conservative defaults chosen)

1. **Shared `jwt.secret`** is duplicated in each service's `application.properties` (default). A
   later phase may externalise it via an environment variable (`JWT_SECRET`) in both builds.
2. **GraphQL (DGS)** stays entirely in the monolith; resolvers will call the extracted services via
   the same REST clients. Not part of the template.
3. **Cut-over data copy** is documented as a manual `sqlite3` dump; an automated Flyway
   `V2__seed` from a live export is left to the domain's phase.
4. **Principal type** in extracted services is the user id from the JWT rather than a full `User`
   entity; controllers needing a profile call `UserServiceClient`.
