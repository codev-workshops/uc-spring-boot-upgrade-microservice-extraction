# Dependency Analysis — `io.spring` monolith

Scope: `src/main/java/io/spring/**` and `src/main/resources/mapper/*.xml`.
Dependencies were measured from `import io.spring.*` statements (same-package references excluded).
"Before" reflects the code prior to the read-service move described in [Recommendation (a)](#a-break-the-application--readservice-cycle-done).

## 1. Package overview

| Package | Role | Key types |
|---|---|---|
| `io.spring.api` | REST controllers (Spring MVC) | `ArticleApi`, `ArticlesApi`, `CommentsApi`, `ProfileApi`, `UsersApi`, `CurrentUserApi`, `TagsApi`, `ArticleFavoriteApi` |
| `io.spring.api.exception` | REST error types and `@ControllerAdvice` handler | `ResourceNotFoundException`, `NoAuthorizationException`, `InvalidAuthenticationException`, `FieldErrorResource` |
| `io.spring.api.security` | Spring Security config, JWT filter | `WebSecurityConfig`, `JwtTokenFilter` |
| `io.spring.graphql` | Netflix DGS datafetchers and mutations | `ArticleDatafetcher`, `ArticleMutation`, `CommentDatafetcher`, `CommentMutation`, `ProfileDatafetcher`, `MeDatafetcher`, `UserMutation`, `RelationMutation` |
| `io.spring.graphql.exception` | GraphQL error mapping | `GraphQLCustomizeExceptionHandler` |
| `io.spring.application` | Query services (CQRS read side), pagination types | `ArticleQueryService`, `CommentQueryService`, `ProfileQueryService`, `TagsQueryService`, `UserQueryService`, `Page`, `CursorPager`, `CursorPageParameter` |
| `io.spring.application.article` / `.user` | Command services and params (write side) | `ArticleCommandService`, `NewArticleParam`, `UserService`, `RegisterParam` |
| `io.spring.application.data` | Read-model DTOs | `ArticleData`, `ArticleDataList`, `CommentData`, `ProfileData`, `UserData`, `ArticleFavoriteCount` |
| `io.spring.core` | Domain models, repository interfaces, domain services | `Article`, `Tag`, `Comment`, `ArticleFavorite`, `User`, `FollowRelation`, `*Repository`, `AuthorizationService`, `JwtService` |
| `io.spring.infrastructure.mybatis.readservice` *(before)* | MyBatis `@Mapper` read interfaces returning `application.data` DTOs | `ArticleReadService`, `ArticleFavoritesReadService`, `CommentReadService`, `UserReadService`, `TagReadService`, `UserRelationshipQueryService` |
| `io.spring.infrastructure.mybatis.mapper` | MyBatis `@Mapper` write-side interfaces for domain aggregates | `ArticleMapper`, `ArticleFavoriteMapper`, `CommentMapper`, `UserMapper` |
| `io.spring.infrastructure.repository` | `core` repository implementations backed by mappers | `MyBatisArticleRepository`, `MyBatisCommentRepository`, `MyBatisArticleFavoriteRepository`, `MyBatisUserRepository` |
| `io.spring.infrastructure.service` | Infrastructure services | `DefaultJwtService` |

## 2. Dependency graph (before)

```
api                                → application, application.article, application.user, application.data, core, api.exception
graphql                            → application, application.article, application.user, application.data, core, api.exception, graphql.exception
graphql.exception                  → api.exception, graphql
application                        → application.data, core, infrastructure.mybatis.readservice
application.article                → application, core
application.user                   → core
application.data                   → application            (DateTimeCursor, Node)
infrastructure.mybatis.readservice → application, application.data, core   (CYCLE with application)
infrastructure.mybatis.mapper      → core
infrastructure.repository          → core, infrastructure.mybatis.mapper
infrastructure.service             → core
api.security                       → core
core                               → (no internal package deps)
```

## 3. Findings

### 3.1 Circular dependency: `application` ↔ `infrastructure.mybatis.readservice`

- `application → readservice`: `ArticleQueryService` imports `ArticleReadService`, `ArticleFavoritesReadService` and `UserRelationshipQueryService`. `CommentQueryService`, `ProfileQueryService`, `TagsQueryService` and `UserQueryService` import the other read services.
- `readservice → application`: the read services return `io.spring.application.data.*` DTOs (`ArticleData`, `CommentData`, `UserData`, `ArticleFavoriteCount`) and take `io.spring.application.Page` / `CursorPageParameter` as parameters.
- Effect: the application layer cannot be compiled, tested or extracted without infrastructure, and the dependency direction between layers is inverted.

### 3.2 Coupling hotspots

- **`application` is the hub that reaches into infrastructure.** It is the only non-infrastructure package that imports `io.spring.infrastructure.*`.
- **`graphql` depends on `api`.** Six GraphQL classes import `io.spring.api.exception.ResourceNotFoundException` (e.g. `src/main/java/io/spring/graphql/ArticleDatafetcher.java`). `ArticleMutation` / `CommentMutation` also use `NoAuthorizationException`; `UserMutation` and `GraphQLCustomizeExceptionHandler` use `InvalidAuthenticationException` / `FieldErrorResource`. The two delivery layers cannot be separated.
- **Controllers bypass the application layer for writes and authorization.** `src/main/java/io/spring/api/ArticleApi.java` calls `ArticleRepository` (`findById`, `remove`) and `AuthorizationService.canWriteArticle` directly. The same pattern exists in other REST controllers and GraphQL mutations, so write rules are duplicated across `api` and `graphql`.
- **Minor intra-`application` cycles:** `application.data → application` (`DateTimeCursor`, `Node`) and `application.article → application` (`DuplicatedArticleValidator → ArticleQueryService`).

### 3.3 Fan-in / fan-out (before)

Fan-in = number of other packages (at the granularity of §2) that import the package. Fan-out = number of packages it imports.

| Package | Fan-in | Fan-out |
|---|---:|---:|
| `core` | 10 | 0 |
| `application` | 5 | 3 |
| `application.data` | 4 | 1 |
| `api.exception` | 3 | 0 |
| `application.article` | 2 | 2 |
| `application.user` | 2 | 1 |
| `infrastructure.mybatis.readservice` | 1 | 3 |
| `infrastructure.mybatis.mapper` | 1 | 1 |
| `infrastructure.repository` | 0 | 2 |
| `api` | 0 | 6 |
| `graphql` | 1 | 7 |

- `core` has the highest fan-in (every layer depends on it; 5 if counted at top level: `api`, `graphql`, `application`, `infrastructure`, `api.security`). It is stable and has no outgoing deps, which is correct.
- `application.data` is the most widely shared DTO package (4 dependents: `api`, `graphql`, `application`, `readservice`).
- Among non-delivery packages, `application` (with its sub-packages) has the widest reach: data, core and infrastructure. The highest absolute fan-out belongs to the delivery layers `graphql` (7) and `api` (6), which is expected for controllers but amplified by their direct use of `core` repositories.

## 4. Database coupling (articles domain)

| Table | Touched by |
|---|---|
| `articles` | `ArticleMapper.xml` (CRUD), `ArticleReadService.xml`, `ArticleFavoritesReadService.xml` |
| `article_tags` | `ArticleMapper.xml` (`insertArticleTagRelation`, joins), `ArticleReadService.xml` |
| `tags` | `ArticleMapper.xml` (`insertTag`, `findTag`), `ArticleReadService.xml`, `TagReadService.xml` |
| `article_favorites` | `ArticleFavoriteMapper.xml`, `ArticleFavoritesReadService.xml`, `ArticleReadService.xml` (favoritedBy filter) |
| `users` | `ArticleReadService.xml`: joined once for author profile columns (`selectArticleData`), and **twice** in list/count queries (`selectArticleIds`, `countArticle`): `AU` for the `author` filter and `AFU` for the `favoritedBy` filter |
| `follows` | Indirectly: feed queries (`ArticleQueryService.findUserFeed*`) first call `UserRelationshipQueryService.followedUsers` (`UserRelationshipQueryService.xml`), then pass the author ids to `ArticleReadService.findArticlesOfAuthors*` |

Implications for microservice extraction:

- Article reads are **not self-contained**. Every article list and detail query joins `users` for author profile data and username-based filters, and feeds depend on the `follows` table owned by the user/profile context.
- An extracted article service would need to (1) store or cache author snapshots (id, username, bio, image), or fetch profiles from a user service, (2) resolve `author` / `favoritedBy` usernames to ids before querying, and (3) receive the followed-author id list from the user service for feeds.
- `article_favorites` stores `user_id` and is filtered by username through `users`, so it needs the same treatment.

Sources: `src/main/resources/mapper/ArticleReadService.xml`, `ArticleMapper.xml`, `ArticleFavoriteMapper.xml`, `ArticleFavoritesReadService.xml`, `UserRelationshipQueryService.xml`.

## 5. Prioritized refactoring recommendations

### (a) Break the `application` ↔ readservice cycle (done)

Moved the six read-service interfaces to `io.spring.application.readservice`:
`ArticleReadService`, `ArticleFavoritesReadService`, `CommentReadService`, `UserReadService`, `TagReadService`, `UserRelationshipQueryService`.

- Updated the `namespace` of the six matching mapper XMLs in `src/main/resources/mapper/`. MyBatis binds statements to interfaces by fully qualified name, so this is required at runtime. Also updated the cross-namespace `<include refid="…ArticleReadService.profileColumns"/>` in `CommentReadService.xml`.
- Updated imports in the five `application` query services and in tests (`TestWithCurrentUser`, `UsersApiTest`), plus the DEBUG logger name in `application.properties`.
- No mapper-scan change was needed. There is no `@MapperScan`; `MyBatisConfig` only enables transactions. The interfaces carry `@Mapper`, and mybatis-spring-boot auto-configuration scans from the `@SpringBootApplication` base package (`io.spring`). Mapper XMLs are located by `mybatis.mapper-locations=mapper/*.xml`.

Resulting graph: the read ports are owned by the application layer. Their only implementations are the MyBatis XML mappers in infrastructure resources (runtime proxies). `io.spring.application` no longer imports `io.spring.infrastructure.*`, so no package cycle crosses a layer boundary:

```
api, graphql           → application, core
application(.*)        → core
infrastructure(.*)     → core   (+ application.data / application.readservice via XML mappers)
core                   → (none)
```

### (b) Move shared exceptions out of `api.exception`

Move `ResourceNotFoundException`, `NoAuthorizationException` and `InvalidAuthenticationException` (and possibly `FieldErrorResource`) to a neutral package such as `io.spring.application.exception` or `io.spring.core.exception`. `graphql` then stops depending on `api`, and the two delivery layers can evolve or be split independently.

### (c) Push write orchestration into `application` command services

Controllers and mutations currently load aggregates from `core` repositories, check `AuthorizationService` and call `save`/`remove`. Move those flows into `ArticleCommandService`, a new `CommentCommandService`, `FavoriteCommandService`, and so on, so that `api` and `graphql` depend only on `application`. This removes duplicated authorization rules and gives one transactional boundary per use case.

### (d) Longer term: split `application` per bounded context

Reorganize `application` (and the matching `core` / `infrastructure` packages) per context: `article` (incl. tags), `comment`, `favorite`, `user`/`profile`. Replace cross-context SQL joins (§4) with explicit ports, such as an `AuthorProfileProvider` and `FollowedAuthorsProvider`. This is the prerequisite for extracting the article service as a microservice with its own database.
