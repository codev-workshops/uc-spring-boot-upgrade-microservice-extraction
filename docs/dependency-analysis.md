# Internal Dependency Analysis

Scope: `src/main/java/io/spring` (93 classes, 20 packages; 21 after the refactor below).
Edges are counted from `import io.spring.*` statements plus fully-qualified `io.spring.*` references in code.
Generated GraphQL code (`io.spring.graphql.types`, `DgsConstants`) is treated as an external sink.

All numbers in sections 1-4 describe the code **before** the quick-win change in section 6.

## 1. Package inventory

| Layer | Package | Classes | Role |
|---|---|---|---|
| root | `io.spring` | 4 | App bootstrap, `MyBatisConfig`, `JacksonCustomizations`, `Util` |
| REST adapter | `io.spring.api` | 8 | Spring MVC controllers |
| | `io.spring.api.exception` | 8 | HTTP exceptions + `CustomizeExceptionHandler` |
| | `io.spring.api.security` | 2 | `JwtTokenFilter`, `WebSecurityConfig` |
| GraphQL adapter | `io.spring.graphql` | 10 | DGS datafetchers / mutations |
| | `io.spring.graphql.exception` | 2 | GraphQL error handling |
| Application | `io.spring.application` | 11 | Query services + pagination types |
| | `io.spring.application.article` | 5 | `ArticleCommandService`, params, validators |
| | `io.spring.application.user` | 8 | `UserService`, params, validators |
| | `io.spring.application.data` | 7 | Read-model DTOs (`ArticleData`, `UserData`, ...) |
| Domain | `io.spring.core.article` | 3 | `Article`, `Tag`, `ArticleRepository` |
| | `io.spring.core.comment` | 2 | `Comment`, `CommentRepository` |
| | `io.spring.core.favorite` | 2 | `ArticleFavorite`, `ArticleFavoriteRepository` |
| | `io.spring.core.user` | 3 | `User`, `FollowRelation`, `UserRepository` |
| | `io.spring.core.service` | 2 | `AuthorizationService`, `JwtService` (port) |
| Infrastructure | `io.spring.infrastructure.repository` | 4 | MyBatis implementations of core repositories |
| | `io.spring.infrastructure.mybatis.mapper` | 4 | Write-side MyBatis mappers |
| | `io.spring.infrastructure.mybatis.readservice` | 6 | Read-side MyBatis mappers (`@Mapper`) |
| | `io.spring.infrastructure.mybatis` | 1 | `DateTimeHandler` type handler |
| | `io.spring.infrastructure.service` | 1 | `DefaultJwtService` |

## 2. Dependency graph

Number in brackets = count of class-level references. `(gen)` = DGS-generated code.

```
io.spring.api ─────────────┬─> api.exception [7]
  (controllers)            ├─> application [9]            ├─> application.article [4]
                           ├─> application.data [8]       ├─> application.user [5]
                           ├─> core.user [10]             ├─> core.article [7]
                           ├─> core.service [3]           ├─> core.comment [2]
                           └─> core.favorite [2]

io.spring.graphql ─────────┬─> graphql.types (gen) [25]   ├─> graphql.exception [4]
  (datafetchers/mutations) ├─> api.exception [9]   <-- adapter-to-adapter coupling
                           ├─> application [15]           ├─> application.article [3]
                           ├─> application.data [11]      ├─> application.user [4]
                           ├─> core.user [19]             ├─> core.article [5]
                           ├─> core.service [3]           ├─> core.comment [2]
                           └─> core.favorite [2]
graphql.exception ─────────┬─> api.exception [2]
                           └─> graphql.types (gen) [2]

api.security ──────────────┬─> core.service [1]
                           └─> core.user [1]

application ───────────────┬─> application.data [7]
  (query services +        ├─> core.user [3]
   pagination types)       └─> infrastructure.mybatis.readservice [9]   <-- app depends on infra
application.data ──────────┴─> application [4]                          <-- CYCLE
application.article ───────┬─> application [1]
                           ├─> core.article [3]
                           └─> core.user [1]
application.user ──────────┴─> core.user [5]

core.article ──────────────┴─> io.spring (Util) [1]
core.user ─────────────────┴─> io.spring (Util) [1]
core.service ──────────────┬─> core.article [1]
                           ├─> core.comment [1]
                           └─> core.user [2]
core.comment, core.favorite: no internal dependencies

infrastructure.repository ─┬─> infrastructure.mybatis.mapper [4]
                           ├─> core.article [3]           ├─> core.user [3]
                           ├─> core.comment [2]           └─> core.favorite [2]
infrastructure.mybatis.mapper ─> core.article [2], core.user [2], core.comment [1], core.favorite [1]
infrastructure.mybatis.readservice ─┬─> application [3]                  <-- CYCLE
                                    ├─> application.data [4]
                                    └─> core.user [1]
infrastructure.service ────┬─> core.service [1]
                           └─> core.user [1]
```

### Layer-level view

```
            ┌──────────────┐      ┌──────────────┐
            │ api (REST)   │<─────│ graphql      │  graphql -> api : 11
            └──────┬───────┘      └──────┬───────┘
          26 │     │ 26           33 │   │ 31
             v     v                 v   v
        ┌─────────────┐  12   ┌───────────┐
        │ application │──────>│   core    │──> io.spring (Util) : 2
        └──┬──────────┘       └───────────┘
         9 │   ^ 7                  ^ 19
           v   │                    │
        ┌──────┴──────────────┐     │
        │   infrastructure    │─────┘
        └─────────────────────┘
```

Expected direction is `adapters -> application -> core <- infrastructure`. Two arrows break it:
`application -> infrastructure` (9) and `graphql -> api` (11).

## 3. Findings

### 3.1 Circular dependencies

One strongly-connected component of 3 packages:

```
application ──> application.data ──> application
     │                                   ^
     └──> infrastructure.mybatis.readservice
```

| Edge | Cause |
|---|---|
| `application -> application.data` | Query services return `ArticleData`, `CommentData`, `ProfileData`, `UserData` |
| `application.data -> application` | `ArticleData`/`CommentData` implement `Node` and use `DateTimeCursor` |
| `application -> infrastructure.mybatis.readservice` | Query services inject `ArticleReadService`, `CommentReadService`, `UserReadService`, `TagReadService`, `ArticleFavoritesReadService`, `UserRelationshipQueryService` |
| `infrastructure.mybatis.readservice -> application` | `ArticleReadService`/`CommentReadService` take `CursorPageParameter` and `Page` |

Root cause: `io.spring.application` mixes two unrelated things — the query services (high-level) and the
generic pagination types (`CursorPager`, `CursorPageParameter`, `Page`, `PageCursor`, `DateTimeCursor`, `Node`; low-level).
Both DTOs and MyBatis mappers need the pagination types, so they are pulled back into the package that depends on them.

No other cycles exist. The `core` domain packages are acyclic.

### 3.2 Fan-in / fan-out

Fan-in = number of internal packages depending on it; fan-out = number of internal packages it depends on.
Instability I = out / (in + out) (0 = stable, 1 = unstable).

| Package | Fan-in | Fan-out | I |
|---|---:|---:|---:|
| `core.user` | **11** | 1 | 0.08 |
| `core.article` | 6 | 1 | 0.14 |
| `application` | 5 | 3 | 0.38 |
| `core.comment` | 5 | 0 | 0.00 |
| `application.data` | 4 | 1 | 0.20 |
| `core.favorite` | 4 | 0 | 0.00 |
| `core.service` | 4 | 3 | 0.43 |
| `api.exception` | 3 | 0 | 0.00 |
| `application.article` | 2 | 3 | 0.60 |
| `application.user` | 2 | 1 | 0.33 |
| `io.spring` (root) | 2 | 0 | 0.00 |
| `graphql.exception` | 1 | 2 | 0.67 |
| `infrastructure.mybatis.mapper` | 1 | 4 | 0.80 |
| `infrastructure.mybatis.readservice` | 1 | 3 | 0.75 |
| `graphql` | 0 | **12** | 1.00 |
| `api` | 0 | **10** | 1.00 |
| `infrastructure.repository` | 0 | 5 | 1.00 |
| `api.security` | 0 | 2 | 1.00 |
| `infrastructure.service` | 0 | 2 | 1.00 |
| `infrastructure.mybatis` | 0 | 0 | 0.00 |

Most-referenced classes:

| Class | References | Referencing packages |
|---|---:|---:|
| `core.user.User` | 34 | 10 |
| `core.article.Article` | 12 | 6 |
| `core.user.UserRepository` | 10 | 5 |
| `api.exception.ResourceNotFoundException` | 10 | 2 (`api`, `graphql`) |
| `core.article.ArticleRepository` | 7 | 4 |
| `application.data.ArticleData` / `CommentData` | 7 each | 4 |

### 3.3 Coupling hotspots

1. **`application` / `application.data` / `readservice` cycle** (section 3.1). Any change to the pagination
   types or the query services recompiles and can break all three packages plus both adapters.
2. **Application layer depends on infrastructure.** All 5 query services inject MyBatis `@Mapper` interfaces from
   `infrastructure.mybatis.readservice` directly. The read side cannot be tested or re-implemented
   (e.g. for microservice extraction) without MyBatis.
3. **GraphQL adapter depends on the REST adapter.** 7 of 10 `graphql` classes import
   `ResourceNotFoundException`, `NoAuthorizationException`, `InvalidAuthenticationException` or `FieldErrorResource`
   from `api.exception`. These classes carry Spring MVC `@ResponseStatus`, so removing or extracting the REST
   layer breaks GraphQL.
4. **Adapters bypass the application layer and duplicate logic.** `api -> core` (26 refs) and `graphql -> core` (31 refs).
   The same write logic is copied in both adapters:
   - favorite/unfavorite: `ArticleFavoriteApi` and `ArticleMutation` both call `ArticleFavoriteRepository`.
   - follow/unfollow: `ProfileApi` and `RelationMutation` both build `FollowRelation` and call `UserRepository`.
   - delete comment: `CommentsApi` and `CommentMutation` both call `AuthorizationService.canWriteComment` and `CommentRepository.remove`.
   A rule change (e.g. "cannot follow yourself") must be made twice, and a repository signature change cascades into both adapters.
5. **`core.user.User` is a god dependency.** Fan-in of 10 packages. It is also the Spring Security principal
   (`(User) authentication.getPrincipal()` in `graphql.SecurityUtil`, `MeDatafetcher`, `UserMutation`, and
   `@AuthenticationPrincipal User` in 6 of 8 controllers), so persistence fields on the entity leak into the web layer.
6. **Domain depends on the root package.** `core.article.Article` and `core.user.User` import `io.spring.Util`, which sits
   next to `RealWorldApplication`, `MyBatisConfig` and `JacksonCustomizations`. The domain is not self-contained.
7. **Hidden string coupling to `application.data`.** `mapper/TransferData.xml` and `mapper/UserReadService.xml` reference
   `io.spring.application.data.*` by fully-qualified name. Moving or renaming these DTOs compiles fine but fails at runtime.
8. **`core.service.AuthorizationService` spans three aggregates** (`Article`, `Comment`, `User`) and is called directly from 4 adapter classes.

## 4. Prioritized refactoring recommendations

| # | Change | Fixes | Effort | Risk |
|---|---|---|---|---|
| 1 | Move pagination types to `io.spring.application.pagination` | Cycle (3.3.1) | S | Low — **done, see section 6** |
| 2 | Move `ResourceNotFoundException`, `NoAuthorizationException`, `InvalidAuthenticationException` to a neutral package (e.g. `io.spring.application.exception`); keep HTTP mapping in `CustomizeExceptionHandler` instead of `@ResponseStatus` | graphql -> api (3.3.3) | S | Low |
| 3 | Move `Util.isEmpty` into `core` (or use `org.springframework.util.StringUtils.hasLength`) | core -> root (3.3.6) | XS | Low |
| 4 | Add application command services: `ArticleFavoriteService`, `ProfileService.follow/unfollow`, `CommentCommandService.create/delete`, and route both adapters through them | Duplication, adapter -> core coupling (3.3.4, 3.3.8) | M | Medium — behaviour must stay identical in REST and GraphQL |
| 5 | Move the six read-service interfaces into `application` (e.g. `io.spring.application.query`) as ports; keep MyBatis XML in `infrastructure` (namespace update in `mapper/*.xml`) | application -> infrastructure (3.3.2) | M | Medium — MyBatis namespace / mapper scanning must be updated |
| 6 | Introduce an `AuthenticatedUser` principal (id, username, email) for the web layer; load `User` only where needed | `User` fan-in (3.3.5) | L | Medium-high — touches every controller and datafetcher |
| 7 | Add an architecture test (ArchUnit) enforcing layer direction and no cycles | Prevents regressions | S | Low |
| 8 | Use MyBatis type aliases for `application.data` DTOs or add an integration test that loads every mapper | String coupling (3.3.7) | XS | Low |

Order rationale: 1-3 are mechanical package moves with no behaviour change and high payoff; 4-5 change wiring and
should be done before any microservice extraction; 6 is the largest change and can follow once 4 is in place.

## 5. Target dependency structure

```
api ─────┐                      ┌──── graphql
         v                      v
     application (services, ports, exceptions, data, pagination)
                     │
                     v
                   core  <────── infrastructure (implements core repositories + application ports)
```

## 6. Implemented quick win: break the `application` cycle

Moved `CursorPageParameter`, `CursorPager`, `DateTimeCursor`, `Node`, `Page` and `PageCursor` from
`io.spring.application` to the new leaf package `io.spring.application.pagination`. No behaviour change; imports updated
in 11 main/test classes. No MyBatis XML references these types.

Result:

| Metric | Before | After |
|---|---|---|
| Package cycles (SCCs) | 1 (3 packages) | **0** |
| Mutual package dependencies | 2 | **0** |
| `application` fan-in / fan-out | 5 / 3 | 3 / 4 |
| `application.pagination` fan-in / fan-out | — | 5 / 0 (stable leaf) |
| `application.data` depends on | `application` | `application.pagination` |
| `readservice` depends on | `application`, `application.data`, `core.user` | `application.pagination`, `application.data`, `core.user` |

The `application -> infrastructure.mybatis.readservice` edge remains (recommendation 5) but is no longer part of a cycle.
