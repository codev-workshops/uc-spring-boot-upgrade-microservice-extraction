# 04 — Strangler wiring design (feature-flag routing, seams, clients, auth, shadow mode)

Status: **Phase 0 design document — no production code is changed by this document.**
Scope: how the monolith (`io.spring.*`) routes traffic between its in-process implementation
and an extracted service, per domain, behind feature flags, with rollback by flipping the flag
off. Migration order: **Favorite -> Comment -> Tag (bundled with Article) -> Article -> User**.

All file paths below are relative to the repository root. Pseudo-diffs are illustrative and
must be re-validated against the code at implementation time.

---

## 0. Guiding principles

1. **Seam = interface already owned by the monolith.** Every domain in this codebase is
   already accessed through a small number of Spring beans: a `core.*Repository` interface
   (write path) and an `infrastructure.mybatis.readservice.*ReadService` MyBatis `@Mapper`
   interface (read path). The strangler seam is placed exactly at those beans so callers in
   `api/`, `application/` and `graphql/` do **not** change.
2. **Flag decides at call time, not at wiring time** (default). A delegating "routing" bean is
   always wired; it consults the flag on every call. This is what makes flips possible without
   a redeploy. `@ConditionalOnProperty` is offered as the simpler alternative when a restart is
   acceptable.
3. **Default OFF everywhere.** With no configuration the monolith behaves exactly as today; the
   existing 27 test files must keep passing untouched.
4. **Read and write are routed independently**, plus a `dual-write` mode for the write path
   and a `shadow` mode for the read path.
5. **Fail towards the monolith.** When the remote path errors, the recommended default is to
   fall back to the in-monolith implementation (which is still fully functional while the
   monolith's table is dual-written), never to silently return wrong data.

---

## 1. Feature-flag routing model

### 1.1 Property surface

```properties
# src/main/resources/application.properties  (all defaults shown; all OFF)
extraction.favorite.enabled=false          # master switch for the domain
extraction.favorite.read=monolith          # monolith | extracted | shadow
extraction.favorite.write=monolith         # monolith | extracted | dual-write
extraction.favorite.base-url=http://localhost:8081
extraction.favorite.connect-timeout=500ms
extraction.favorite.read-timeout=1500ms
extraction.favorite.fallback=monolith      # monolith | empty | fail   (see §3.3)

extraction.comment.enabled=false           # same sub-keys
extraction.tag.enabled=false               # same sub-keys (tag is bundled with article, see §2.4)
extraction.article.enabled=false           # same sub-keys
extraction.user.enabled=false              # same sub-keys
```

Semantics:

| key | values | meaning |
|---|---|---|
| `enabled` | `true/false` | Master switch. When `false` every sub-flag is ignored and the domain is 100 % monolith. Lets ops "arm" a configuration (`read=extracted`) and flip a single boolean. |
| `read` | `monolith` | reads served by the MyBatis implementation |
|  | `extracted` | reads served by the remote client |
|  | `shadow` | monolith result is returned; remote is called asynchronously and diffed (§5) |
| `write` | `monolith` | writes go to the monolith table only |
|  | `dual-write` | write to monolith table first (source of truth), then to the remote service; remote failure is logged/metered, not surfaced |
|  | `extracted` | writes go to the remote service only; the monolith table is no longer updated |
| `fallback` | `monolith` / `empty` / `fail` | behaviour when `read=extracted` and the remote call fails (§3.3) |

Env-var override uses Spring Boot relaxed binding out of the box:
`EXTRACTION_FAVORITE_ENABLED=true EXTRACTION_FAVORITE_READ=extracted`.

### 1.2 `ExtractionProperties`

Proposed new class (Phase 1), following the constructor-injection convention of the repo:

```java
// src/main/java/io/spring/infrastructure/extraction/ExtractionProperties.java
@ConfigurationProperties(prefix = "extraction")
@Validated
@Getter @Setter
public class ExtractionProperties {
  private DomainRoute favorite = new DomainRoute();
  private DomainRoute comment  = new DomainRoute();
  private DomainRoute tag      = new DomainRoute();
  private DomainRoute article  = new DomainRoute();
  private DomainRoute user     = new DomainRoute();

  @Getter @Setter
  public static class DomainRoute {
    private boolean enabled = false;
    private ReadMode read = ReadMode.MONOLITH;
    private WriteMode write = WriteMode.MONOLITH;
    private Fallback fallback = Fallback.MONOLITH;
    private URI baseUrl = URI.create("http://localhost:8081");
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofMillis(1500);

    public boolean readsRemote()  { return enabled && read == ReadMode.EXTRACTED; }
    public boolean shadows()      { return enabled && read == ReadMode.SHADOW; }
    public boolean writesRemote() { return enabled && write != WriteMode.MONOLITH; }
    public boolean writesLocal()  { return !enabled || write != WriteMode.EXTRACTED; }
  }
  public enum ReadMode  { MONOLITH, EXTRACTED, SHADOW }
  public enum WriteMode { MONOLITH, DUAL_WRITE, EXTRACTED }
  public enum Fallback  { MONOLITH, EMPTY, FAIL }
}
```

Registered with `@EnableConfigurationProperties(ExtractionProperties.class)` on a new
`ExtractionConfig` `@Configuration` (not on `RealWorldApplication`, so `@WebMvcTest` slices
stay unaffected). Existing slice tests that `@MockBean` a repository or read service continue to
work because they never see the routing bean.

### 1.3 Flipping without redeploy

Three mechanisms, in increasing order of complexity. Recommendation for Phase 1: **(a) + (b)**.

(a) **Externalised configuration, restart-free within a rolling restart budget.** The properties
    are read from env vars / `SPRING_APPLICATION_JSON` / an external `application.properties`
    (`--spring.config.additional-location`). Flipping requires a process restart but no new
    image. This is the minimum viable model and is what the default `@ConfigurationProperties`
    bean supports.

(b) **Runtime refresh via Actuator `@RefreshScope`-free approach.** Because this project does
    not use Spring Cloud, the cheapest runtime refresh is a small `RouteFlags` holder bean that
    wraps `ExtractionProperties` in `AtomicReference<DomainRoute>` per domain and exposes an
    **authenticated, admin-only** endpoint or JMX operation, e.g.
    `POST /internal/extraction/{domain}` with body `{"enabled":true,"read":"extracted"}`.
    The routing beans read the `AtomicReference` on every call, so the flip is effective on the
    next request with no restart. Rollback = same call with `{"enabled":false}`.
    Security: the endpoint must be excluded from the public `WebSecurityConfig` rules and
    protected by a separate shared secret header or network policy (open question O-1).

(c) **Spring Cloud Config / Consul / a feature-flag SaaS (Unleash, LaunchDarkly).** Deferred;
    would pull `spring-cloud-starter-*` into a Spring Boot 2.6.3 build. Out of scope for Phase 1.

Rollback recipe (any mechanism): set `extraction.<domain>.enabled=false`. Because the master
switch short-circuits every sub-flag, a single key restores 100 % monolith behaviour. While the
domain was in `write=dual-write` the monolith table is complete, so rollback is lossless. While
the domain was in `write=extracted` the monolith table has drifted; rollback then requires a
back-fill from the extracted service (risk R-3) — this is why the checklist (§6) keeps
`dual-write` on until the read cut-over has soaked.

### 1.4 Bean selection strategies

| strategy | how | pros | cons |
|---|---|---|---|
| **Delegating router (recommended)** | `Routing<X>` bean is `@Primary`, holds both `MyBatis<X>` and `Remote<X>` and picks per call | flips at runtime, supports shadow/dual-write, single wiring path in tests | one extra indirection; both impls always instantiated |
| `@ConditionalOnProperty` | `@ConditionalOnProperty(prefix="extraction.favorite", name="read", havingValue="extracted")` on `Remote<X>`, `matchIfMissing=true` on `MyBatis<X>` | zero runtime overhead, simplest | requires restart to flip; cannot express dual-write/shadow |

The rest of this document assumes the delegating router.

---

## 2. Routing seams per domain

### 2.1 Phase 1 — Favorite (concrete)

#### 2.1.1 Current shape

Read path (`src/main/java/io/spring/application/ArticleQueryService.java`):

```java
private ArticleFavoritesReadService articleFavoritesReadService;   // MyBatis @Mapper interface

private void setFavoriteCount(List<ArticleData> articles) {
  List<ArticleFavoriteCount> favoritesCounts =
      articleFavoritesReadService.articlesFavoriteCount(ids);
  Map<String, Integer> countMap = ...;
  articles.forEach(a -> a.setFavoritesCount(countMap.get(a.getId())));  // <-- unboxes Integer -> int
}
private void setIsFavorite(List<ArticleData> articles, User currentUser) {
  Set<String> favoritedArticles = articleFavoritesReadService.userFavorites(ids, currentUser);
  ...
}
private void fillExtraInfo(String id, User user, ArticleData articleData) {
  articleData.setFavorited(articleFavoritesReadService.isUserFavorite(user.getId(), id));
  articleData.setFavoritesCount(articleFavoritesReadService.articleFavoriteCount(id));
  ...
}
```

`ArticleFavoritesReadService` is *already an interface*
(`src/main/java/io/spring/infrastructure/mybatis/readservice/ArticleFavoritesReadService.java`,
annotated `@Mapper`, SQL in `src/main/resources/mapper/ArticleFavoritesReadService.xml`).
The `articlesFavoriteCount` SQL `LEFT JOIN`s `articles` -> `article_favorites` and `GROUP BY`s,
so every requested id that exists in `articles` comes back with a count (0 when no favorites).
**This is the current null-vs-0 contract**: `countMap.get(id)` is never `null` today only because
of the `LEFT JOIN` on the article table; `ArticleData.favoritesCount` is a primitive `int`, so a
missing entry would throw `NullPointerException` on auto-unboxing, not serialise as `null`.

Write path:
- `src/main/java/io/spring/api/ArticleFavoriteApi.java` — `POST/DELETE /articles/{slug}/favorite`
  uses `ArticleFavoriteRepository.save/find/remove` then `ArticleQueryService.findBySlug` to
  build the response.
- `src/main/java/io/spring/graphql/ArticleMutation.java` — `favoriteArticle` / `unfavoriteArticle`
  (`@DgsMutation(field = MUTATION.FavoriteArticle / UnfavoriteArticle)`) use the same
  `ArticleFavoriteRepository` calls; the response article is later resolved through
  `ArticleDatafetcher.getArticle` -> `ArticleQueryService.findById`.
- `src/main/java/io/spring/infrastructure/repository/MyBatisArticleFavoriteRepository.java`
  (`@Repository`, mapper XML `src/main/resources/mapper/ArticleFavoriteMapper.xml`).

Hidden favorite dependency (must be listed, not routed in Phase 1):
- `src/main/resources/mapper/ArticleReadService.xml` — `selectArticleIds` joins
  `article_favorites AF` and `users AFU` to implement the `favoritedBy=<username>` filter used by
  `ArticleReadService.queryArticles`, `countArticle`, `findArticlesWithCursor`
  (`GET /articles?favoritedBy=` and GraphQL `Profile.favorites`). This read stays in the monolith
  in Phase 1 and is only correct while `write` is `monolith` or `dual-write`. Moving it requires
  a `GET /favorites?userId=` list endpoint on the Favorite service and a change to
  `ArticleQueryService.findRecentArticles/findRecentArticlesWithCursor` (risk R-2).
- Deleting an article (`ArticleMapper.xml` `delete from articles where id = ...`) does **not**
  cascade to `article_favorites` (no FKs in `V1__create_tables.sql`). Orphan rows exist today;
  the extracted service will inherit the same semantics unless it subscribes to article deletion.

#### 2.1.2 Seam — read path

Keep the MyBatis interface name stable for the callers (`ArticleQueryService` and its
`@Import`-based test `src/test/java/io/spring/application/article/ArticleQueryServiceTest.java`
do not change). Introduce a domain-owned port and make the MyBatis mapper *one* implementation.

```diff
+ // src/main/java/io/spring/application/favorite/FavoriteQueryPort.java   (application layer, no MyBatis import)
+ public interface FavoriteQueryPort {
+   boolean isUserFavorite(String userId, String articleId);
+   int articleFavoriteCount(String articleId);
+   List<ArticleFavoriteCount> articlesFavoriteCount(List<String> ids);
+   Set<String> userFavorites(List<String> ids, User currentUser);
+ }

  // src/main/java/io/spring/infrastructure/mybatis/readservice/ArticleFavoritesReadService.java
  @Mapper
- public interface ArticleFavoritesReadService { ...same 4 methods... }
+ public interface ArticleFavoritesReadService extends FavoriteQueryPort { /* same 4 methods, MyBatis XML unchanged */ }

+ // src/main/java/io/spring/infrastructure/extraction/favorite/RemoteFavoriteQueryService.java
+ @Component
+ public class RemoteFavoriteQueryService implements FavoriteQueryPort {
+   private final FavoriteServiceClient client;
+   public int articleFavoriteCount(String articleId) { return client.count(articleId).getCount(); }
+   public List<ArticleFavoriteCount> articlesFavoriteCount(List<String> ids) {
+     return client.counts(ids).stream().map(d -> new ArticleFavoriteCount(d.getArticleId(), d.getCount())).collect(toList());
+   }
+   public Set<String> userFavorites(List<String> ids, User u) { return client.userFavorites(u.getId(), ids).getArticleIds(); }
+   public boolean isUserFavorite(String userId, String articleId) { return client.userFavorites(userId, List.of(articleId)).getArticleIds().contains(articleId); }
+ }

+ // src/main/java/io/spring/infrastructure/extraction/favorite/RoutingFavoriteQueryService.java
+ @Primary @Service
+ public class RoutingFavoriteQueryService implements FavoriteQueryPort {
+   private final ArticleFavoritesReadService monolith;
+   private final RemoteFavoriteQueryService remote;
+   private final RouteFlags flags;                 // §1.3(b); reads extraction.favorite.*
+   private final ShadowComparator shadow;          // §5
+   private final RouteMetrics metrics;             // §3.5
+
+   public List<ArticleFavoriteCount> articlesFavoriteCount(List<String> ids) {
+     return route("articlesFavoriteCount",
+         () -> monolith.articlesFavoriteCount(ids),
+         () -> remote.articlesFavoriteCount(ids));
+   }
+   /* other 3 methods identical pattern */
+
+   private <T> T route(String op, Supplier<T> local, Supplier<T> extracted) {
+     DomainRoute r = flags.favorite();
+     if (r.shadows()) { T v = local.get(); shadow.compareAsync("favorite", op, v, extracted); return v; }
+     if (!r.readsRemote()) return metrics.timed("monolith", op, local);
+     try { return metrics.timed("extracted", op, extracted); }
+     catch (FavoriteServiceException e) { return fallback(r, op, local, e); }   // §3.3
+   }
+ }

  // src/main/java/io/spring/application/ArticleQueryService.java
- private ArticleFavoritesReadService articleFavoritesReadService;
+ private FavoriteQueryPort articleFavoritesReadService;   // field name kept; @AllArgsConstructor unchanged
```

Why `ArticleFavoritesReadService extends FavoriteQueryPort` rather than a rename: the
`@MybatisTest`-based tests and the `@Import`-based `ArticleQueryServiceTest` reference the
existing type; keeping it avoids touching test files. `ArticleQueryServiceTest` imports
`ArticleQueryService` with the real MyBatis beans — with `FavoriteQueryPort` injected the test
context will now need a `FavoriteQueryPort` bean; the MyBatis mapper satisfies it as long as the
routing bean is not on the test's import list (it is not, since it lives in a new package that
the `@MybatisTest` slice does not scan). Verify in Phase 1 (checklist step 6).

#### 2.1.3 Seam — write path

`ArticleFavoriteRepository` (`src/main/java/io/spring/core/favorite/ArticleFavoriteRepository.java`)
is already the interface both `ArticleFavoriteApi` and `ArticleMutation` depend on. Seam:

```diff
+ // src/main/java/io/spring/infrastructure/extraction/favorite/RoutingArticleFavoriteRepository.java
+ @Primary @Repository
+ public class RoutingArticleFavoriteRepository implements ArticleFavoriteRepository {
+   private final MyBatisArticleFavoriteRepository monolith;
+   private final RemoteArticleFavoriteRepository remote;   // wraps FavoriteServiceClient.favorite/unfavorite
+   private final RouteFlags flags;
+
+   public void save(ArticleFavorite f) {
+     DomainRoute r = flags.favorite();
+     if (r.writesLocal()) monolith.save(f);                       // source of truth first
+     if (r.writesRemote()) writeRemote(r, () -> remote.save(f));  // dual-write: swallow+meter; extracted: propagate
+   }
+   public void remove(ArticleFavorite f) { /* same */ }
+   public Optional<ArticleFavorite> find(String articleId, String userId) {
+     return flags.favorite().readsRemote() ? remote.find(articleId, userId) : monolith.find(articleId, userId);
+   }
+ }
```

Notes:
- `MyBatisArticleFavoriteRepository` stays `@Repository` (its `@MybatisTest` in
  `src/test/java/io/spring/infrastructure/favorite/MyBatisArticleFavoriteRepositoryTest.java`
  autowires it by concrete type). The routing bean is `@Primary` so all `ArticleFavoriteRepository`
  injection points (`ArticleFavoriteApi`, `ArticleMutation`) receive it without edits.
- `save` is idempotent in the monolith (`find` then `insert`); the remote `PUT`/`POST` must be
  idempotent too (the service's PK `(article_id,user_id)` makes duplicates a no-op).
- **Ordering in `dual-write`:** monolith first, then remote. If the remote fails, the request
  still succeeds and the discrepancy is metered (`extraction.dualwrite.failure{domain=favorite}`)
  for the reconciliation job (checklist step 10). If the monolith fails, nothing is sent remotely.
- `unfavoriteArticle` in both entry points does `find(...).ifPresent(remove)`. With
  `read=extracted` the `find` goes remote, so a remote outage would skip the delete rather than
  fail; when `fallback=monolith` the router falls back to the local row instead.
- The response of `POST/DELETE /articles/{slug}/favorite` is computed by
  `articleQueryService.findBySlug(slug, user)` — i.e. via the *read* seam. In `dual-write` +
  `read=monolith` the response reflects the monolith table (consistent). In `read=extracted` it
  reflects the service; if the remote write is asynchronous in the service, the response may lag
  (risk R-4). Recommendation: the Favorite service writes synchronously.
- Alternative considered and rejected: routing at the controller (`ArticleFavoriteApi`) with a
  reverse proxy forwarding `/articles/{slug}/favorite` to the service. Rejected for Phase 1
  because the response envelope (`{"article": ArticleData}`) requires article + author + follow
  data that the Favorite service does not own; it would need to call back into the monolith.

#### 2.1.4 Favorite call-site inventory

| layer | file | member | path |
|---|---|---|---|
| api | `api/ArticleFavoriteApi.java` | `favoriteArticle`, `unfavoriteArticle` | write (`ArticleFavoriteRepository`) + read (`ArticleQueryService.findBySlug`) |
| graphql | `graphql/ArticleMutation.java` | `favoriteArticle`, `unfavoriteArticle` | write (`ArticleFavoriteRepository`) |
| graphql | `graphql/ArticleDatafetcher.java` | every `@DgsData` that returns articles (`userFeed`, `userFavorites`, `userArticles`, `getArticles`, `getArticle`, `getCommentArticle`) | read (via `ArticleQueryService`) |
| application | `application/ArticleQueryService.java` | `setFavoriteCount`, `setIsFavorite`, `fillExtraInfo(String,User,ArticleData)` | read (`ArticleFavoritesReadService`) |
| application | `application/ArticleQueryService.java` | `findRecentArticles`, `findRecentArticlesWithCursor` (`favoritedBy` filter) | read, **not routed in Phase 1** (§2.1.1) |
| infrastructure | `infrastructure/repository/MyBatisArticleFavoriteRepository.java`, `mapper/ArticleFavoriteMapper.xml`, `mapper/ArticleFavoritesReadService.xml`, `mapper/TransferData.xml` (`favoriteCount` resultMap) | — | monolith implementation, unchanged |
| api (indirect) | `api/ArticleApi.java`, `api/ArticlesApi.java` | all read endpoints | read via `ArticleQueryService` |

### 2.2 Comment

Seams: `core.comment.CommentRepository` (write) and
`infrastructure.mybatis.readservice.CommentReadService` (read, used only by
`application.CommentQueryService`). Same pattern: `CommentQueryPort` (methods `findById`,
`findByArticleId`, `findByArticleIdWithCursor`) implemented by `CommentReadService extends
CommentQueryPort`, `RemoteCommentQueryService`, `RoutingCommentQueryService (@Primary)`; and
`RoutingCommentRepository (@Primary)` over `MyBatisCommentRepository` + `RemoteCommentRepository`.

Cross-domain enrichment that stays in the monolith: `CommentQueryService` calls
`UserRelationshipQueryService.isUserFollowing/followingAuthors` to set `author.following`; the
Comment service returns `CommentData` *without* `following`, the monolith fills it. `CommentData`
embeds `ProfileData` (author) — the Comment service must denormalise author `username/bio/image`
at write time or call the monolith's `GET /profiles/{username}` (open question O-3).

Call-sites:

| layer | file | member |
|---|---|---|
| api | `api/CommentsApi.java` | `createComment` (save + `findById`), `getComments` (`findByArticleId`), `deleteComment` (`findById` on repository + `remove`) |
| graphql | `graphql/CommentMutation.java` | `createComment`, `removeComment` |
| graphql | `graphql/CommentDatafetcher.java` | `articleComments` (`findByArticleIdWithCursor`) |
| application | `application/CommentQueryService.java` | `findById`, `findByArticleId`, `findByArticleIdWithCursor` |
| infrastructure | `infrastructure/repository/MyBatisCommentRepository.java`, `mapper/CommentMapper.xml`, `mapper/CommentReadService.xml` | monolith impl |

Both entry points resolve the parent `Article` through `ArticleRepository.findBySlug` before
touching comments; that lookup stays in the monolith until the Article phase.

### 2.3 Tag (bundled with Article)

Tags have no write API of their own: they are written inside `MyBatisArticleRepository.save`
(`ArticleMapper.xml` `insertTag` / `insertArticleTagRelation`) and read by
`TagReadService.all()` -> `application.TagsQueryService.allTags()` -> `api/TagsApi.java`
`getTags` and `graphql/TagDatafetcher.java` `getTags`, plus the `tag=` filter inside
`ArticleReadService.xml`. Because the write side is inseparable from Article, Tag is routed with
the Article flag; `extraction.tag.enabled` is kept for the *read* seam only (`TagQueryPort`
over `TagReadService`), which allows the `GET /tags` endpoint to be moved early as a low-risk
warm-up if desired.

### 2.4 Article

Seams: `core.article.ArticleRepository` (write; used by `ArticleCommandService`, `ArticleApi`,
`ArticleFavoriteApi`, `CommentsApi`, `ArticleMutation`, `CommentMutation`) and
`infrastructure.mybatis.readservice.ArticleReadService` (read; used only by
`ArticleQueryService`). Routing shape identical to Favorite. Additional considerations:

- `ArticleQueryService` is the composition point that joins Article + Favorite + User-follow
  data. After Article extraction it becomes a pure orchestrator over three ports
  (`ArticleQueryPort`, `FavoriteQueryPort`, `UserRelationshipQueryService`). The
  `favoritedBy` / `tag` / `author` filters currently expressed as SQL joins in
  `ArticleReadService.xml` become two-step lookups (ids from Favorite/User service, then
  articles by id) — the largest behavioural risk in the whole program (R-2).
- `application/article/DuplicatedArticleValidator.java` (Bean Validation on `NewArticleParam`)
  calls `ArticleQueryService.findBySlug` — passes through the seam automatically.
- `ArticleRepositoryTransactionTest` covers the `@Transactional` article+tags write; the remote
  implementation loses that local transaction (the Article service owns it instead).

Call-sites (write): `api/ArticleApi.java` (`getArticle`, `updateArticle`, `deleteArticle`),
`api/ArticlesApi.java` (`createArticle`), `api/ArticleFavoriteApi.java`, `api/CommentsApi.java`
(`findArticle`), `graphql/ArticleMutation.java` (all five mutations), `graphql/CommentMutation.java`,
`application/article/ArticleCommandService.java`. Read: everything listed in §2.1.4 under
`ArticleQueryService`, plus `application/article/DuplicatedArticleValidator.java`.

### 2.5 User

User is last because every other domain depends on it for author/profile enrichment and for
authentication (`JwtTokenFilter` loads the principal via `UserRepository.findById` on every
request). Seams: `core.user.UserRepository` (write + auth lookup), `UserReadService`
(`UserQueryService`, `ProfileQueryService`), `UserRelationshipQueryService` (follow graph, used
by `ArticleQueryService`, `CommentQueryService`, `ProfileQueryService`).

Call-sites: `api/UsersApi.java` (`createUser`, `userLogin`), `api/CurrentUserApi.java`,
`api/ProfileApi.java` (`follow`, `unfollow`, `getProfile`), `api/security/JwtTokenFilter.java`
(`findById` per request — **must be cached or the User service becomes a per-request hop**, R-5),
`graphql/UserMutation.java`, `graphql/RelationMutation.java`, `graphql/MeDatafetcher.java`,
`graphql/ProfileDatafetcher.java`, `application/user/UserService.java`,
`application/user/DuplicatedEmailValidator.java`, `application/user/DuplicatedUsernameValidator.java`,
`application/UserQueryService.java`, `application/ProfileQueryService.java`.

---

## 3. Client design

Per AGENTS.md: one dedicated client class per remote service, DTOs local to the caller, graceful
failure handling. Everything lives under `io.spring.infrastructure.extraction.<domain>` in the
monolith (the monolith is the *caller* here; the extracted service will have its own
`UserServiceClient`-style clients pointing back).

### 3.1 `FavoriteServiceClient`

```java
// src/main/java/io/spring/infrastructure/extraction/favorite/FavoriteServiceClient.java
@Component
public class FavoriteServiceClient {
  private final RestTemplate rest;          // built from RestTemplateBuilder with the domain's timeouts
  private final ExtractionProperties.DomainRoute route;
  private final AuthTokenPropagator auth;   // §4

  public FavoriteCountDto count(String articleId)                       // GET  /favorites/count?articleId=
  public List<FavoriteCountDto> counts(List<String> articleIds)         // POST /favorites/counts  {"articleIds":[...]}   (POST to avoid URL-length limits)
  public UserFavoritesDto userFavorites(String userId, List<String> ids)// POST /favorites/query   {"userId":..,"articleIds":[...]}
  public void favorite(String articleId)                                // POST   /favorites/{articleId}   (user from JWT)
  public void unfavorite(String articleId)                              // DELETE /favorites/{articleId}
}

// DTOs — local to the monolith, not shared
@Value class FavoriteCountDto  { String articleId; int count; }
@Value class UserFavoritesDto  { String userId; Set<String> articleIds; }
class FavoriteServiceException extends RuntimeException { /* wraps HTTP/IO errors with URL + status */ }
```

`RestTemplate` is chosen over `WebClient` because the project is servlet-based
(`spring-boot-starter-web`) and has no reactive dependency; adding `spring-webflux` only for a
client is unwarranted. `RestTemplateBuilder` (auto-configured in Boot 2.6) supplies
`setConnectTimeout(route.getConnectTimeout())` / `setReadTimeout(route.getReadTimeout())`.

### 3.2 Timeouts and retries

- Connect 500 ms, read 1.5 s (defaults in §1.1). Read enrichment for a 20-article page is a
  single `counts` + single `userFavorites` call, so the p99 budget added to
  `GET /articles` is ≤ 2 × read-timeout in the worst case.
- Retries: **none for writes** (favorite is idempotent, but the retry hides latency problems and
  the dual-write reconciliation covers loss). For reads, at most **one** retry on connect
  timeout / 503 only, never on read timeout (the request may have completed server-side and
  doubling the latency budget is worse than falling back). Implemented with a plain loop, or
  with `spring-retry` if the team prefers annotations (extra dependency; open question O-4).
- Circuit breaker (optional): `resilience4j-spring-boot2` `@CircuitBreaker(name="favorite",
  fallbackMethod=...)` around the client methods. Recommended for Phase 2+ once there is
  production traffic; for Phase 1 the fallback path in the router already prevents cascading
  failure. Keep the door open by making `FavoriteServiceClient` methods the only place that
  throws `FavoriteServiceException`.

### 3.3 Graceful failure — recommendation and trade-off

Options for `read=extracted` when the Favorite service is unreachable:

| `fallback` | behaviour | trade-off |
|---|---|---|
| `monolith` (**recommended default for Phase 1**) | call the MyBatis implementation | correct as long as `write` is `monolith`/`dual-write`; silently stale once `write=extracted` — must be paired with an alert on `extraction.fallback{domain=favorite}` |
| `empty` | `favoritesCount=0`, `favorited=false` | never stale, always available, but **changes observable data** (a count of 0 is indistinguishable from a real 0) and, for the write endpoints, a POST that succeeded would echo `favorited:false` |
| `fail` | throw -> HTTP 503 via `CustomizeExceptionHandler` | loudest; unacceptable for `GET /articles` because favorites are a decoration on a page that is otherwise available |

Null-vs-0 contract: `ArticleData.favoritesCount` is `int`, and the JSON contract (RealWorld
spec, and `ArticleDatafetcher.buildArticleResult` -> GraphQL `Int!`) requires an integer, so
returning `null` is not an option — the remote implementation must return a count for **every**
requested id (`0` for unknown ids), mirroring the current `LEFT JOIN` semantics; otherwise
`setFavoriteCount` NPEs on unboxing. `RemoteFavoriteQueryService.articlesFavoriteCount` must
therefore post-fill missing ids with `0` regardless of what the service returns. This is a
contract test for the harness (§5).

### 3.4 Auth on the client

Every call attaches `Authorization: Token <jwt>` (§4). Read calls for anonymous users
(`currentUser == null`) are made without the header; the service must accept unauthenticated
`count`/`counts`.

### 3.5 Observability

- Log at `INFO` once per flag change (`RouteFlags`), and at `WARN` on every fallback with
  domain, op, status/exception class (never the token).
- Micrometer (bundled with `spring-boot-starter-actuator`, which needs to be added) timers
  `extraction.call{domain,op,route=monolith|extracted,outcome=ok|error|fallback}` and counters
  `extraction.dualwrite.failure{domain,op}`, `extraction.shadow.mismatch{domain,op}`.
- MDC key `route` added by the router so existing MyBatis DEBUG logging
  (`logging.level.io.spring.infrastructure.mybatis.*=DEBUG` in `application.properties`) can be
  correlated.

---

## 4. Auth propagation

Current mechanism: `api/security/JwtTokenFilter.java` reads `Authorization: Token <jwt>`,
`DefaultJwtService.getSubFromToken` verifies HS512 with `jwt.secret` and returns the user id
(`sub`), then `UserRepository.findById` loads the `User` principal. GraphQL uses the same filter
(`/graphql` is `permitAll` but the filter still populates the `SecurityContext`;
`graphql/SecurityUtil.getCurrentUser` reads it).

Design:

1. **Forward the caller's token unchanged.** `AuthTokenPropagator` (a `ClientHttpRequestInterceptor`
   on the `RestTemplate`) reads the incoming `Authorization` header from
   `RequestContextHolder.currentRequestAttributes()` (servlet request) and copies it verbatim onto
   the outbound call. This works for REST and GraphQL alike because both run inside the servlet
   request thread. For the async shadow path (§5) the header must be captured **before**
   handing off to the executor and passed explicitly (`RequestContextHolder` is thread-local).
2. **Same secret, same algorithm in the service.** The Favorite service gets its own copy of
   `JwtService`/`DefaultJwtService` (the class is 50 lines; do not create a shared module — per
   AGENTS.md DTOs and infra live in each service) configured with the *same* `jwt.secret`
   value, injected via `JWT_SECRET` env var in both deployments (the secret currently
   hard-coded in `application.properties` must move to an env var first — checklist step 1,
   open question O-2). The service validates `Token <jwt>`, extracts `sub` = user id.
3. **Current-user resolution in the service.** The Favorite service needs only the user *id*
   (PK of `article_favorites` is `(article_id, user_id)`); it does not need to load the `User`.
   Its `JwtTokenFilter` variant sets a lightweight principal `CurrentUser(id)` — no call back to
   the monolith's `/user` on the hot path. If a later service needs profile data it uses a
   `UserServiceClient` to the monolith (`GET /profiles/{username}`), per AGENTS.md.
4. **Service-to-service calls without a user** (reconciliation job, shadow comparisons for
   anonymous requests): none required for Favorite reads; if introduced, mint a short-lived token
   with a reserved `sub` (`system:monolith`) using the same secret rather than adding a second
   auth scheme.
5. Token expiry (`jwt.sessionTime=86400`) is validated identically on both sides; clock skew
   between hosts is the only new failure mode (R-6).

---

## 5. Parallel-run / shadow mode

`extraction.<domain>.read=shadow` makes the router return the monolith result while invoking the
remote path asynchronously and comparing. Hook:

```java
public interface ShadowComparator {
  <T> void compareAsync(String domain, String op, T monolithResult, Supplier<T> extracted);
}
```

Default implementation `LoggingShadowComparator`: runs `extracted` on a bounded
`ThreadPoolTaskExecutor` (queue 100, discard-oldest — shadowing must never block or back-pressure
the request), normalises both results (sort lists/sets, `0`-fill counts as per §3.3), compares
with `equals`, and on mismatch logs at `WARN` with a redacted diff and increments
`extraction.shadow.mismatch`. The comparator is a Spring bean so the orchestrator's test harness
can replace it (`@MockBean`/`@Primary` in tests, or a `RecordingShadowComparator` that captures
pairs for assertion or writes them to a file for offline diffing). Write paths are *not*
shadowed — `dual-write` plus the reconciliation query (count rows per `(article_id,user_id)` in
both stores) is the write-side equivalent.

Exit criterion for moving from `shadow` to `extracted`: 0 mismatches over an agreed soak window
on both REST (`GET /articles`, `GET /articles/{slug}`, `POST/DELETE /articles/{slug}/favorite`)
and GraphQL (`articles`, `article`, `favoriteArticle`, `unfavoriteArticle`) traffic.

---

## 6. Risks, Phase 1 checklist, open questions

### Risks

| id | risk | mitigation |
|---|---|---|
| R-1 | Router bean breaks existing slice tests (`@WebMvcTest`, `@MybatisTest`, `@Import(ArticleQueryService.class)`) by pulling `ExtractionProperties`/`RestTemplate` into the context | Keep all routing/extraction beans in `io.spring.infrastructure.extraction`, registered only via `ExtractionConfig`; `@MybatisTest` and `@WebMvcTest` do not scan it. Run the full suite after each step. |
| R-2 | `favoritedBy` filter and GraphQL `Profile.favorites` still join `article_favorites` locally | Stay on `dual-write` until the Favorite service exposes a list endpoint and `ArticleQueryService` is changed to two-step lookup; covered by shadow diff of `GET /articles?favoritedBy=`. |
| R-3 | Rollback after `write=extracted` loses writes made only to the service | Never go `write=extracted` before a back-fill script exists; treat `extracted` as the final state, not a rollback-able one. |
| R-4 | Read-after-write inconsistency in `POST /favorite` response when read and write are on different sides | Flip `read` and `write` together for Favorite; service writes synchronously. |
| R-5 | `JwtTokenFilter` does a `UserRepository.findById` per request — a hidden hot-path dependency for the User phase | Out of Phase 1 scope; note for the User design (needs a cache or a token that carries username/email claims). |
| R-6 | Secret sharing / clock skew between monolith and service | Same `JWT_SECRET` env var, NTP; monitor 401 rate on the service. |
| R-7 | Latency added to every article list request | Batch endpoints (`counts`, `userFavorites/query`), tight timeouts, fallback to monolith. |
| R-8 | Spotless/google-java-format on the new code requires JDK 11 toolchain | Documented in repo build facts; CI must use JDK 11. |

### Ordered implementation checklist — Phase 1 (Favorite)

1. Move `jwt.secret` to `${JWT_SECRET:<current value>}` in `application.properties` (no
   behaviour change; enables sharing).
2. Add `spring-boot-starter-actuator` (metrics) — verify `./gradlew build -x jacocoTestCoverageVerification` stays green.
3. Add `ExtractionProperties`, `ExtractionConfig`, `RouteFlags` with all flags default OFF; unit
   test binding from env vars.
4. Introduce `FavoriteQueryPort`; make `ArticleFavoritesReadService extends FavoriteQueryPort`;
   change `ArticleQueryService` field type. Run the suite (expect green, no test edits).
5. Add `FavoriteServiceClient`, DTOs, `FavoriteServiceException`, `AuthTokenPropagator`;
   tests with `MockRestServiceServer`.
6. Add `RemoteFavoriteQueryService` + `RoutingFavoriteQueryService (@Primary)`; new tests
   asserting: flags OFF -> monolith only; `read=extracted` -> remote; remote failure ->
   fallback per mode; 0-fill of missing counts.
7. Add `RemoteArticleFavoriteRepository` + `RoutingArticleFavoriteRepository (@Primary)`;
   tests for `monolith`, `dual-write` (remote failure swallowed + metered), `extracted`.
8. Add `ShadowComparator` + `LoggingShadowComparator`; test through a `RecordingShadowComparator`.
9. Optional: `POST /internal/extraction/{domain}` runtime flip endpoint (decide O-1 first).
10. Reconciliation script/endpoint comparing `article_favorites` row set with the service.
11. Deploy Favorite service (separate slice), run with `enabled=true, write=dual-write,
    read=shadow`; soak; then `read=extracted`; then (after back-fill tooling) `write=extracted`.

Each step is independently mergeable and keeps every flag OFF, so `main` remains
behaviourally identical to today until step 11.

### Open questions

- **O-1** Runtime flip endpoint: is an in-process admin endpoint acceptable, or must flips go
  through the deployment platform (env var + rolling restart)? Conservative default in this
  design: env var + restart (§1.3 a); the endpoint is optional.
- **O-2** `jwt.secret` is committed in `application.properties`. Moving it to an env var is a
  prerequisite for sharing it safely with the Favorite service; who owns rotating it?
- **O-3** For Comment (Phase 2): should the Comment service denormalise author profile fields or
  call back into the monolith per comment page? Conservative default: denormalise
  `username/bio/image` at write time and accept staleness of bio/image on old comments.
- **O-4** Retry library: hand-rolled single retry vs `spring-retry` vs Resilience4j from day one.
  Conservative default: hand-rolled, no new dependency in Phase 1.
- **O-5** Does the Favorite service need to react to article deletion (there is no FK cascade
  today, so orphan favorites already exist in the monolith)? Default: no, preserve current
  semantics.
- **O-6** Should `extraction.tag.*` exist at all given Tag writes are inseparable from Article
  (§2.3)? Default: keep it, read-only seam.
