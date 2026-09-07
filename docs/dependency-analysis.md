# Internal Dependency Analysis

**Scope:** `src/main/java/io/spring/**` (91 production classes, 20 packages) plus MyBatis XML mappers in `src/main/resources/mapper/`.
**Method:** Every `import io.spring.*` statement was resolved to its declaring package and aggregated into a package-level directed graph. Same-package references are excluded. Fully-qualified in-body references (there is one: `ArticleData implements io.spring.application.Node`) were checked manually. DGS-generated code (`io.spring.graphql.types.*`, `io.spring.graphql.DgsConstants`) is treated as a single external-ish node `graphql.types(generated)`.

---

## 1. Package Inventory

| Layer | Package | Classes | Role |
|---|---|---|---|
| Root | `io.spring` | 4 | `RealWorldApplication`, `MyBatisConfig`, `JacksonCustomizations`, `Util` |
| REST API | `io.spring.api` | 8 | `@RestController`s (`ArticleApi`, `ArticlesApi`, `ArticleFavoriteApi`, `CommentsApi`, `CurrentUserApi`, `ProfileApi`, `TagsApi`, `UsersApi`) |
| REST API | `io.spring.api.exception` | 8 | Exception types + `CustomizeExceptionHandler` + error resources |
| REST API | `io.spring.api.security` | 2 | `JwtTokenFilter`, `WebSecurityConfig` |
| GraphQL API | `io.spring.graphql` | 10 | DGS datafetchers / mutations, `SecurityUtil` |
| GraphQL API | `io.spring.graphql.exception` | 2 | `AuthenticationException`, `GraphQLCustomizeExceptionHandler` |
| Application | `io.spring.application` | 11 | Query services (`ArticleQueryService`, `CommentQueryService`, `ProfileQueryService`, `TagsQueryService`, `UserQueryService`) + paging primitives (`Page`, `CursorPager`, `CursorPageParameter`, `DateTimeCursor`, `Node`, `PageCursor`) |
| Application | `io.spring.application.article` | 5 | `ArticleCommandService`, params, validators |
| Application | `io.spring.application.user` | 8 | `UserService`, params, validators |
| Application | `io.spring.application.data` | 7 | Read-model DTOs (`ArticleData`, `CommentData`, `ProfileData`, `UserData`, `UserWithToken`, `ArticleDataList`, `ArticleFavoriteCount`) |
| Domain | `io.spring.core.article` | 3 | `Article`, `Tag`, `ArticleRepository` |
| Domain | `io.spring.core.comment` | 2 | `Comment`, `CommentRepository` |
| Domain | `io.spring.core.favorite` | 2 | `ArticleFavorite`, `ArticleFavoriteRepository` |
| Domain | `io.spring.core.user` | 3 | `User`, `FollowRelation`, `UserRepository` |
| Domain | `io.spring.core.service` | 2 | `AuthorizationService` (static), `JwtService` (interface) |
| Infra | `io.spring.infrastructure.repository` | 4 | `MyBatis*Repository` implementations |
| Infra | `io.spring.infrastructure.mybatis.mapper` | 4 | Write-side MyBatis mapper interfaces |
| Infra | `io.spring.infrastructure.mybatis.readservice` | 6 | Read-side MyBatis mapper interfaces (CQRS read models) |
| Infra | `io.spring.infrastructure.mybatis` | 1 | `DateTimeHandler` (type handler) |
| Infra | `io.spring.infrastructure.service` | 1 | `DefaultJwtService` |

---

## 2. Package Dependency Graph

### 2.1 Layered view

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  ENTRY POINTS                                                                │
 │                                                                              │
 │   io.spring.api ────────────────┐         io.spring.graphql ───────────────┐ │
 │   (8 controllers)               │         (10 datafetchers/mutations)      │ │
 │   fan-out 10                    │         fan-out 12                       │ │
 │   io.spring.api.security        │         io.spring.graphql.exception      │ │
 └────────┬──────────┬─────────────┼──────────────┬──────────┬────────────────┘ │
          │          │             │              │          │                  │
          │          ▼             │              ▼          │                  │
          │   io.spring.api.exception  ◀─────────────────────┘ (graphql AND graphql.exception
          │   (fan-in 3: api, graphql, graphql.exception)       import REST exceptions)
          │
          ▼                                                    ▼
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  APPLICATION                                                                 │
 │                                                                              │
 │   io.spring.application.article ──▶ io.spring.application                    │
 │   io.spring.application.user                     ▲   │   ▲                   │
 │                                                  │   │   │                   │
 │              ┌───────────────────────────────────┘   │   └──────────────┐    │
 │              │                                       ▼                  │    │
 │   io.spring.application.data  ◀──────────────────────┘                  │    │
 │              │  (ArticleData/CommentData implement Node,                │    │
 │              │   return DateTimeCursor)                                 │    │
 └──────────────┼──────────────────────────────────────────────────────────┼────┘
                │                                                          │
                │      ╔════════════════ CYCLE ═══════════════════╗        │
                │      ║ application ⇄ application.data           ║        │
                │      ║ application ⇄ infrastructure.readservice ║        │
                │      ╚══════════════════════════════════════════╝        │
                ▼                                                          │
 ┌──────────────────────────────────────────────────────────────────────────┼────┐
 │  INFRASTRUCTURE                                                          │    │
 │                                                                          │    │
 │   io.spring.infrastructure.mybatis.readservice ──────────────────────────┘    │
 │        (imports Page, CursorPageParameter, *Data DTOs from application)       │
 │                                                                               │
 │   io.spring.infrastructure.repository ──▶ io.spring.infrastructure.mybatis.mapper
 │   io.spring.infrastructure.service                                            │
 └──────┬──────────────────────────────────────┬─────────────────────────────────┘
        │                                      │
        ▼                                      ▼
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  DOMAIN (core)                                                               │
 │                                                                              │
 │   io.spring.core.service ──▶ core.article, core.comment, core.user           │
 │        (AuthorizationService is the only class spanning 3 contexts)          │
 │                                                                              │
 │   io.spring.core.user  ◀════ fan-in 11 (every layer, every context)          │
 │   io.spring.core.article  fan-in 6                                           │
 │   io.spring.core.comment  fan-in 5                                           │
 │   io.spring.core.favorite fan-in 4                                           │
 │        │                                                                     │
 │        └──▶ io.spring (Util.isEmpty — root package leaks into domain)        │
 └──────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Full edge list (package → package, with import weight)

Weight = number of `import` statements; files = number of distinct source files carrying the edge.

```
io.spring.api                         → io.spring.application                  9 imports / 8 files
io.spring.api                         → io.spring.core.user                   10 imports / 7 files
io.spring.api                         → io.spring.application.data             8 imports / 6 files
io.spring.api                         → io.spring.api.exception                7 imports / 5 files
io.spring.api                         → io.spring.core.article                 7 imports / 4 files
io.spring.api                         → io.spring.application.user             5 imports / 2 files
io.spring.api                         → io.spring.application.article          4 imports / 2 files
io.spring.api                         → io.spring.core.service                 3 imports / 3 files
io.spring.api                         → io.spring.core.comment                 2 imports / 1 file
io.spring.api                         → io.spring.core.favorite                2 imports / 1 file
io.spring.api.security                → io.spring.core.service                 1 / 1
io.spring.api.security                → io.spring.core.user                    1 / 1

io.spring.graphql                     → io.spring.graphql.types(generated)    42 imports / 9 files
io.spring.graphql                     → io.spring.application                 15 imports / 7 files
io.spring.graphql                     → io.spring.core.user                   12 imports / 8 files
io.spring.graphql                     → io.spring.application.data            11 imports / 6 files
io.spring.graphql                     → io.spring.api.exception                9 imports / 7 files
io.spring.graphql                     → io.spring.core.article                 4 / 2
io.spring.graphql                     → io.spring.application.user             4 / 1
io.spring.graphql                     → io.spring.graphql.exception            4 / 4
io.spring.graphql                     → io.spring.application.article          3 / 1
io.spring.graphql                     → io.spring.core.service                 3 / 3
io.spring.graphql                     → io.spring.core.comment                 2 / 1
io.spring.graphql                     → io.spring.core.favorite                2 / 1
io.spring.graphql.exception           → io.spring.api.exception                2 / 1
io.spring.graphql.exception           → io.spring.graphql.types(generated)     2 / 1

io.spring.application                 → io.spring.infrastructure.mybatis.readservice   9 / 5   ◀ layer inversion
io.spring.application                 → io.spring.application.data             7 / 4
io.spring.application                 → io.spring.core.user                    3 / 3
io.spring.application.article         → io.spring.core.article                 3 / 2
io.spring.application.article         → io.spring.application                  1 / 1
io.spring.application.article         → io.spring.core.user                    1 / 1
io.spring.application.user            → io.spring.core.user                    5 / 4
io.spring.application.data            → io.spring.application                  3 / 2   ◀ back-edge (cycle)

io.spring.core.service                → io.spring.core.user                    2 / 2
io.spring.core.service                → io.spring.core.article                 1 / 1
io.spring.core.service                → io.spring.core.comment                 1 / 1
io.spring.core.article                → io.spring                              1 / 1   (Util)
io.spring.core.user                   → io.spring                              1 / 1   (Util)

io.spring.infrastructure.repository   → io.spring.infrastructure.mybatis.mapper 4 / 4
io.spring.infrastructure.repository   → io.spring.core.article                 3 / 1
io.spring.infrastructure.repository   → io.spring.core.user                    3 / 1
io.spring.infrastructure.repository   → io.spring.core.comment                 2 / 1
io.spring.infrastructure.repository   → io.spring.core.favorite                2 / 1
io.spring.infrastructure.mybatis.mapper → io.spring.core.article               2 / 1
io.spring.infrastructure.mybatis.mapper → io.spring.core.user                  2 / 1
io.spring.infrastructure.mybatis.mapper → io.spring.core.comment               1 / 1
io.spring.infrastructure.mybatis.mapper → io.spring.core.favorite              1 / 1
io.spring.infrastructure.mybatis.readservice → io.spring.application.data      4 / 4   ◀ back-edge (cycle)
io.spring.infrastructure.mybatis.readservice → io.spring.application           3 / 2   ◀ back-edge (cycle)
io.spring.infrastructure.mybatis.readservice → io.spring.core.user             1 / 1
io.spring.infrastructure.service      → io.spring.core.service                 1 / 1
io.spring.infrastructure.service      → io.spring.core.user                    1 / 1
```

---

## 3. Circular Dependencies

Tarjan SCC over the package graph yields exactly **one non-trivial strongly connected component**:

```
{ io.spring.application,
  io.spring.application.data,
  io.spring.infrastructure.mybatis.readservice }
```

It is composed of two overlapping 2-cycles that share `io.spring.application`.

### Cycle A — `application` ⇄ `application.data`

| Direction | Evidence |
|---|---|
| `application` → `application.data` | `ArticleQueryService`, `CommentQueryService`, `ProfileQueryService`, `UserQueryService` return `ArticleData`, `CommentData`, `ProfileData`, `UserData`, `ArticleDataList`, `ArticleFavoriteCount`. |
| `application.data` → `application` | `ArticleData implements io.spring.application.Node` and `getCursor()` returns `new DateTimeCursor(updatedAt)`. `CommentData` does the same with `createdAt`. |

**Root cause:** the cursor-paging abstraction (`Node`, `DateTimeCursor`, `PageCursor`, `CursorPager`, `CursorPageParameter`) lives in the same package as the query services, so a DTO that participates in paging has to reach back up into the service package.

**Severity:** Low-to-medium. It is a sub-package cycle within one layer, so it does not cross an architectural boundary, but it prevents `application.data` from being extracted as a standalone DTO module (e.g. into a cross-service contract library).

### Cycle B — `application` ⇄ `infrastructure.mybatis.readservice`

| Direction | Evidence |
|---|---|
| `application` → `readservice` | All five query services are constructor-injected with concrete `*ReadService` MyBatis mapper interfaces (`ArticleReadService`, `ArticleFavoritesReadService`, `UserRelationshipQueryService`, `CommentReadService`, `UserReadService`, `TagReadService`). |
| `readservice` → `application` | `ArticleReadService` and `CommentReadService` accept `Page` and `CursorPageParameter<DateTime>` as method params. All four read services return `application.data` DTOs, and the XML mappers hard-code `resultMap type="io.spring.application.data.ArticleData"` etc. |

**Root cause:** the read-side ports are not defined in the application layer. The MyBatis `@Mapper` interfaces *are* the ports, so the application layer imports infrastructure directly. This is the classic Dependency-Inversion violation: the write side does it correctly (`core.article.ArticleRepository` interface → `infrastructure.repository.MyBatisArticleRepository` impl), but the read side does not.

**Severity:** High. It is a cross-layer cycle (application ↔ infrastructure). It means:
- Query services cannot be unit-tested without MyBatis on the classpath (tests in `src/test/java/io/spring/application/*` are all `@SpringBootTest` / `@MybatisTest` slices, not pure unit tests).
- The persistence technology cannot be swapped or mocked per-service without touching `io.spring.application`.
- When extracting a microservice, the read services and DTOs must be moved together with the query services as an atomic unit.

### Not a cycle, but worth noting

- `io.spring.core.article` / `io.spring.core.user` → `io.spring` (`Util.isEmpty`). The root package is a leaf here (fan-out 0), so there is no cycle, but the domain layer depending on the application root is an inverted dependency that would break if `Util` ever imported anything from `core`.

---

## 4. Fan-In / Fan-Out Metrics

Fan-in = number of distinct packages that import this package. Fan-out = number of distinct packages this package imports. Instability `I = fan_out / (fan_in + fan_out)` (Martin); 0 = maximally stable, 1 = maximally unstable.

| Package | Fan-in | Fan-out | I | Assessment |
|---|---:|---:|---:|---|
| `io.spring.core.user` | **11** | 1 | 0.08 | Most depended-upon package in the codebase. Every layer and every domain context touches it. |
| `io.spring.core.article` | 6 | 1 | 0.14 | Stable, appropriate for a domain aggregate. |
| `io.spring.application` | 5 | 3 | 0.38 | **Hub package** — high fan-in AND part of the only SCC. Changes here ripple both up (api, graphql) and down (readservice, data). |
| `io.spring.core.comment` | 5 | 0 | 0.00 | Pure leaf. |
| `io.spring.core.favorite` | 4 | 0 | 0.00 | Pure leaf. |
| `io.spring.core.service` | 4 | 3 | 0.43 | Depends on 3 domain contexts; `AuthorizationService` is a cross-context coupling point. |
| `io.spring.application.data` | 4 | 1 | 0.20 | Would be a perfect stable DTO module if not for the back-edge to `application`. |
| `io.spring.api.exception` | 3 | 0 | 0.00 | Leaf — but imported by the **GraphQL** layer, which is a cross-API-style leak. |
| `io.spring` (root) | 2 | 0 | 0.00 | `Util` used by domain. |
| `io.spring.application.article` | 2 | 3 | 0.60 | Fine. |
| `io.spring.application.user` | 2 | 1 | 0.33 | Fine. |
| `io.spring.graphql.types(generated)` | 2 | 0 | 0.00 | Generated; 42 imports from `graphql` alone. |
| `io.spring.infrastructure.mybatis.readservice` | 1 | 3 | 0.75 | Should be I≈1 (pure implementation detail) but is imported by `application`. |
| `io.spring.infrastructure.mybatis.mapper` | 1 | 4 | 0.80 | Correct. |
| `io.spring.graphql.exception` | 1 | 2 | 0.67 | Fine. |
| `io.spring.graphql` | 0 | **12** | 1.00 | Highest fan-out. Entry point; touches every layer including domain repositories. |
| `io.spring.api` | 0 | **10** | 1.00 | Second-highest fan-out; same pattern as `graphql`. |
| `io.spring.infrastructure.repository` | 0 | 5 | 1.00 | Correct for an adapter. |
| `io.spring.api.security` | 0 | 2 | 1.00 | Fine. |
| `io.spring.infrastructure.service` | 0 | 2 | 1.00 | Fine. |
| `io.spring.infrastructure.mybatis` | 0 | 0 | — | Isolated (`DateTimeHandler`, wired via `MyBatisConfig`). |

### Class-level fan-in (top 10 imported classes)

| Class | Import count | Note |
|---|---:|---|
| `core.user.User` | 27 | Used as `@AuthenticationPrincipal` in every controller/datafetcher, passed into every query service, and used as a MyBatis `@Param` in `ArticleFavoritesReadService.userFavorites`. |
| `core.article.Article` | 11 | |
| `core.user.UserRepository` | 10 | Injected directly into 5 controllers/datafetchers and `JwtTokenFilter`. |
| `api.exception.ResourceNotFoundException` | 10 | 7 of those imports are from the GraphQL package. |
| `core.article.ArticleRepository` | 7 | Injected directly into `ArticleApi`, `ArticleFavoriteApi`, `CommentsApi`, `ArticleMutation`, `CommentMutation`. |
| `application.data.CommentData` | 7 | |
| `application.data.ArticleData` | 7 | |
| `application.data.UserData` | 6 | |
| `core.comment.Comment` | 5 | |
| `application.ArticleQueryService` | 5 | |

---

## 5. Coupling Hotspots

Ordered by blast radius (how many packages/files would need to change).

### H1 — `core.user.User` is the universal currency (fan-in 11 packages, 27 imports)

`User` is simultaneously: the JPA-less domain aggregate, the Spring Security principal, the parameter type for every read query (`ArticleQueryService.findBySlug(slug, User)`), and a MyBatis query parameter. Any change to `User` (e.g. adding a field, splitting identity from profile, replacing the entity with a `UserId` value type for a users-service extraction) forces recompilation in `api`, `api.security`, `graphql`, `application`, `application.article`, `application.user`, `core.service`, `infrastructure.repository`, `infrastructure.mybatis.mapper`, `infrastructure.mybatis.readservice`, `infrastructure.service`.

For microservice extraction this is the single largest obstacle: **articles, comments and favorites all hold a `String userId` and all query services expect a full `User` object**, so a users-service cannot be carved out without introducing a `CurrentUser`/`UserId` abstraction first.

### H2 — Controllers and datafetchers bypass the application layer

10 of 18 entry-point classes inject domain repositories directly:

| Entry point | Repositories injected |
|---|---|
| `api.ArticleApi` | `ArticleRepository` |
| `api.ArticleFavoriteApi` | `ArticleRepository`, `ArticleFavoriteRepository` |
| `api.CommentsApi` | `ArticleRepository`, `CommentRepository` |
| `api.ProfileApi` | `UserRepository` |
| `api.UsersApi` | `UserRepository` |
| `graphql.ArticleDatafetcher` | `UserRepository` |
| `graphql.ArticleMutation` | `ArticleRepository` |
| `graphql.CommentMutation` | `ArticleRepository`, `CommentRepository` |
| `graphql.RelationMutation` | `UserRepository` |
| `graphql.UserMutation` | `UserRepository` |

Consequences: business rules (`AuthorizationService.canWriteArticle`, lookup-then-authorize-then-mutate flows, `FollowRelation` creation) are **duplicated between the REST and GraphQL layers** — e.g. the update/delete article flow in `ArticleApi` is copy-pasted in `ArticleMutation`; the follow/unfollow flow exists in both `ProfileApi` and `RelationMutation`. This is why `api` and `graphql` have fan-out 10 and 12: they are doing application-layer work. Any change to a repository signature cascades into up to 5 entry-point classes across two API styles.

### H3 — `application` ⇄ `infrastructure.mybatis.readservice` cycle

Described in §3 Cycle B. The five query services are effectively glue over MyBatis mapper interfaces; the "port" is the mapper. Changing a SQL query's shape (e.g. `ArticleReadService.findArticlesWithCursor` returning IDs vs. full rows) changes the application layer.

The XML mappers deepen the coupling: `ArticleReadService.xml` joins `articles ⨝ article_tags ⨝ tags ⨝ article_favorites ⨝ users`, and `CommentReadService.xml` joins `comments ⨝ users`. The `articles` read model therefore physically depends on the `users` and `article_favorites` tables — a data-level cross-context dependency that no Java import reveals.

### H4 — GraphQL layer depends on REST exception types

`graphql` (7 files, 9 imports) and `graphql.exception` (2 imports) import `api.exception.ResourceNotFoundException`, `NoAuthorizationException`, `InvalidAuthenticationException`, and `FieldErrorResource`. The GraphQL API cannot be built or deployed without the REST API's exception package, and `GraphQLCustomizeExceptionHandler` reuses REST's `FieldErrorResource` for GraphQL error payloads. These exceptions are semantically application-level ("not found", "not authorized") but are homed in a transport-specific package.

### H5 — `core.service.AuthorizationService` couples three domain contexts

`AuthorizationService.canWriteComment(User, Article, Comment)` is the only class that imports `core.article`, `core.comment` and `core.user` at once. It is `static`, so it cannot be mocked or replaced, and it is called from both `api` and `graphql`. Splitting comments into their own service requires either duplicating this class or turning it into a remote check.

### H6 — Paging primitives co-located with query services

`Node`, `DateTimeCursor`, `PageCursor`, `CursorPager`, `CursorPageParameter`, `Page` live in `io.spring.application` alongside `*QueryService`. This is what creates Cycle A and half of Cycle B: DTOs and mapper interfaces need the paging types, and by importing them they import the whole services package.

### H7 — Domain layer depends on root package `Util`

`Article` and `User` call `io.spring.Util.isEmpty`. Trivial today, but it means `core` cannot be lifted out as a dependency-free module.

---

## 6. Prioritized Refactoring Recommendations

Ordered by (value ÷ effort), with the microservice-extraction goal in mind. Each item is independently shippable.

### P1 — Break Cycle B: introduce read-side ports in the application layer

**Effort:** Medium. **Removes:** the only cross-layer cycle; makes `application` independently testable.

1. Create `io.spring.application.port` (or keep in `application`) with plain Java interfaces: `ArticleReadPort`, `CommentReadPort`, `UserReadPort`, `TagReadPort`, `ArticleFavoriteReadPort`, `UserRelationshipPort` — same method signatures as today's `*ReadService` mappers.
2. Make the MyBatis `@Mapper` interfaces in `infrastructure.mybatis.readservice` **extend** those ports (`public interface ArticleReadService extends ArticleReadPort {}`). MyBatis is fine with inherited abstract methods; no XML changes.
3. Change constructor parameters in `ArticleQueryService`, `CommentQueryService`, `ProfileQueryService`, `UserQueryService`, `TagsQueryService` to the port types.

After this, `application → infrastructure` disappears and `readservice → application` becomes a legitimate downward dependency on ports/DTOs.

### P2 — Break Cycle A: move paging primitives into their own package

**Effort:** Low (mechanical move + import fixes). **Removes:** `application.data → application` back-edge.

Move `Node`, `DateTimeCursor`, `PageCursor`, `CursorPager`, `CursorPageParameter`, `Page` to `io.spring.application.paging` (or `io.spring.core.paging` if you want them available to domain repositories later). `application.data` then depends only on `application.paging`, and `application.data` becomes a leaf DTO package that can be copied into an extracted service's contract.

### P3 — Push repository access out of controllers/datafetchers into command services

**Effort:** Medium-high (touches 10 entry-point classes). **Removes:** H2 duplication; drops `api` fan-out from 10 → ~5 and `graphql` from 12 → ~7.

- Add `CommentCommandService` (create/delete with authorization), extend `ArticleCommandService` with `delete(slug, currentUser)` and `favorite/unfavorite`, and add `FollowService` / extend `UserService` with `follow/unfollow`.
- Have the services throw application-level exceptions (see P4) so both REST and GraphQL call the same method and only translate the exception.
- Entry points should then import only `application.*` and `core.user.User` (as principal).

This is also the step that makes microservice extraction tractable: once the REST and GraphQL layers stop reaching into `ArticleRepository`, a remote `ArticleServiceClient` can replace `ArticleCommandService` behind the same interface without touching either API layer.

### P4 — Move transport-agnostic exceptions out of `api.exception`

**Effort:** Low. **Removes:** H4 (`graphql → api.exception`).

Move `ResourceNotFoundException`, `NoAuthorizationException`, `InvalidAuthenticationException`, `InvalidRequestException` to `io.spring.application.exception` (they are thrown by application logic, not by HTTP plumbing). Leave `CustomizeExceptionHandler`, `ErrorResource`, `ErrorResourceSerializer`, `FieldErrorResource` in `api.exception`. Give `GraphQLCustomizeExceptionHandler` its own small error-item mapping instead of reusing `FieldErrorResource`.

### P5 — Introduce a lightweight `UserId`/`CurrentUser` type to decouple from `core.user.User`

**Effort:** Medium (27 import sites, but mostly mechanical). **Reduces:** H1 fan-in from 11 packages toward ~4.

- Query services only need `user.getId()` — change signatures to accept `String currentUserId` (nullable) instead of `User`.
- `ArticleFavoritesReadService.userFavorites(ids, User currentUser)` → take `String userId`.
- `AuthorizationService` methods → take `String userId`.
- Controllers/datafetchers still receive `User` from Spring Security and unwrap it once at the boundary.

After this, `articles`/`comments`/`favorites` contexts depend on `core.user` only for the principal type at the edge, which is exactly the seam needed for a users-service `UserServiceClient` + `UserData` DTO.

### P6 — Make `AuthorizationService` instance-based and context-local

**Effort:** Low. **Removes:** H5.

Convert to a Spring `@Service` with an interface, and split by context: `ArticleAuthorization.canWrite(userId, Article)` in `core.article`, `CommentAuthorization.canWrite(userId, Article, Comment)` in `core.comment`. Combined with P3, each command service owns its own authorization, and the static cross-context class disappears.

### P7 — Remove `core → io.spring.Util`

**Effort:** Trivial. **Removes:** H7.

Inline `Util.isEmpty` (or use `org.apache.commons.lang3.StringUtils.isEmpty` if already on the classpath; otherwise `s == null || s.isEmpty()`). `core` becomes a zero-dependency module.

### P8 — Enforce the layering with an architecture test

**Effort:** Low. **Prevents regression** of P1–P7.

Add ArchUnit (`com.tngtech.archunit:archunit-junit5`) to `build.gradle` test dependencies and a single test asserting:
- `..core..` depends on nothing outside `..core..` and the JDK/Lombok/Joda,
- `..application..` does not depend on `..infrastructure..`,
- `..api..` and `..graphql..` do not depend on `..infrastructure..` or `..core..*Repository`,
- `..graphql..` does not depend on `..api..`,
- `slices().matching("io.spring.(**)").should().beFreeOfCycles()`.

---

## 7. Extraction Readiness Summary

With P1–P5 done, the dependency graph becomes a strict DAG:

```
api ─┐
     ├─▶ application.* ─▶ application.paging
graphql ┘      │           application.data
               │           application.exception
               ▼
          core.{article,comment,favorite,user}   (no outbound edges)
               ▲
infrastructure.{repository,mybatis.*,service} ───┘  (implements application ports + core repos)
```

At that point each domain context (articles+tags, comments, favorites, users+profiles) maps to a vertical slice (`api/graphql → application → core → infrastructure`) whose only cross-slice edges are (a) the `userId` string carried by `Article`/`Comment`/`ArticleFavorite`, and (b) the SQL joins to `users` in `ArticleReadService.xml` / `CommentReadService.xml` — both of which are replaced by a `UserServiceClient` returning `ProfileData` in the extracted service, per the standards in `AGENTS.md`.
