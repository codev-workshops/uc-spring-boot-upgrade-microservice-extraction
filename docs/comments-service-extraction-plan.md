# Comments microservice extraction plan

Status: proposed; this document does not implement the extraction.

Baseline: `main` at `7acb734f7e71e80318d7c9f2fe755f637a61ee79`, inspected on 2026-09-07.

### Relationship to existing pull requests

An open extraction stack already exists outside this baseline: [PR #21](https://github.com/codev-workshops/uc-spring-boot-upgrade-microservice-extraction/pull/21) contains cross-domain design/test work, and [PR #23](https://github.com/codev-workshops/uc-spring-boot-upgrade-microservice-extraction/pull/23) contains a comments implementation stacked on favorite extraction. Their descriptions report prior approvals; this proposal does not supersede those approvals or claim that no extraction implementation exists on other branches.

This document answers the comments-first planning request against the current `main`. Before implementing it, reconcile with that stack and reuse reviewed work rather than creating a competing service. In particular, PR #23 describes `comment-service/`, monolith-side enrichment/authorization, shared JWT verification, and dual-write/fallback options; this proposal uses the provisional name `comments-service/`, service-owned authorization with protected lookup callbacks, no shared signing key, and a maintenance-window single-writer cutover. These are explicit design alternatives requiring an owner decision, not changes made by this PR. Review of PR descriptions is not verification of their code or test claims.

## 1. Goal and scope

Extract comment creation, retrieval, deletion, authorization, and persistence into an independently buildable and deployable service. Preserve the existing public REST routes and GraphQL schema through compatibility adapters in the monolith. The extracted service is the only comment writer after cutover and owns a separate SQLite database.

Keep Java 11, Spring Boot 2.6.3, and the existing dependency versions. Follow the repository's [extraction standards](../AGENTS.md): independent Gradle build, MyBatis XML mappers, constructor injection, service-local communication DTOs, and no shared database or shared domain-model module.

Non-goals:

- Extract users, articles, tags, favorites, or the GraphQL server.
- Upgrade Spring Boot, Java, the frontend, or unrelated dependencies.
- Introduce a message broker, distributed transactions, or a service mesh as prerequisites.
- Redesign public APIs or silently change existing validation, cursor, or deletion semantics.
- Change coverage thresholds or existing security policies to make builds pass.

## 2. Existing seam and constraints

Comments already have an ID-based domain model, repository interface, MyBatis repository implementation, mapper, and dedicated table. This is a useful boundary, not an already independent capability.

| Current touchpoint | Coupling to address |
| --- | --- |
| [`CommentsApi`](../src/main/java/io/spring/api/CommentsApi.java) | Resolves articles, writes comments directly, authorizes deletion, and assembles responses. |
| [`CommentMutation`](../src/main/java/io/spring/graphql/CommentMutation.java) | Duplicates comment command orchestration for GraphQL. |
| [`CommentQueryService`](../src/main/java/io/spring/application/CommentQueryService.java) | Accepts domain `User`; enriches comments through follow lookups. |
| [`CommentReadService.xml`](../src/main/resources/mapper/CommentReadService.xml) | Joins `comments` to `users` and imports a SQL fragment from the article mapper. |
| [`TransferData.xml`](../src/main/resources/mapper/TransferData.xml) | Shares profile and comment result maps with article read models. |
| [`AuthorizationService`](../src/main/java/io/spring/core/service/AuthorizationService.java) | Allows the comment author OR article author to delete a comment. |
| [`JwtTokenFilter`](../src/main/java/io/spring/api/security/JwtTokenFilter.java) | Loads a domain `User` from the monolith database after token validation. |
| [`CommentDatafetcher`](../src/main/java/io/spring/graphql/CommentDatafetcher.java) | Builds cursor connections and DGS local-context maps. |
| [`ProfileDatafetcher`](../src/main/java/io/spring/graphql/ProfileDatafetcher.java) | Resolves `Comment.author` through comment local context. |
| [`ArticleDatafetcher`](../src/main/java/io/spring/graphql/ArticleDatafetcher.java) | Resolves `Comment.article`; currently assumes a different context shape from the comment datafetcher. |

The [schema](../src/main/resources/db/migration/V1__create_tables.sql) contains logical article/user references but no foreign-key declarations. Lack of foreign keys does not remove article existence, ownership, or lifecycle requirements.

## 3. Target architecture

```text
Browser / existing API clients
              |
              v
Monolith: public REST + GraphQL adapters
              |
              | CommentsServiceClient (REST, authenticated transport)
              v
Comments service ----------------------------> comments.db
    |
    +-- ArticleServiceClient --> monolith internal article lookup
    +-- UserServiceClient ----> monolith internal identity/profile lookups
                                      |
                                      v
                          monolith database (no live comment access)
```

The temporary HTTP dependency is bidirectional, but no callback may re-enter a comments adapter. Internal monolith lookup handlers must use only local article/user repositories or services, never `CommentsServiceClient`. This prevents recursive request loops; it does not make comments independent of monolith availability. Use separate bounded connection pools and an end-to-end request deadline to avoid resource exhaustion across callbacks.

### Ownership

- Comments service: comment IDs, body, article ID, author ID, timestamps, comment write rules, comment querying, and deletion authorization.
- Monolith: article existence/slug/owner, user authentication/account state, public profiles, follows, and public API composition.
- No users, follows, articles, passwords, or JWT signing keys are copied into the comments database.
- The retained monolith `comments` table is a frozen rollback artifact after cutover, not a second source of truth.

### Proposed service layout

Create `comments-service/` with its own `build.gradle`, `settings.gradle`, Gradle wrapper scripts/JAR/properties matching the root wrapper, and `src/main` / `src/test` trees. It must not depend on the root project or import monolith classes.

Use the existing `io.spring` package/layer pattern within this separate artifact:

- `api/`: REST endpoints, validation, service authentication, error mapping.
- `core/comment/`: `Comment`, `CommentRepository`, ownership policy based on trusted IDs.
- `application/`: command/query services and application-owned read ports.
- `application/data/`: service-local request/response DTOs.
- `application/pagination/`: local paging abstractions, separate from query services.
- `infrastructure/repository/`, `infrastructure/mybatis/`: implementations and XML mappers.
- `infrastructure/client/`: dedicated article/user REST clients and failure translation.
- `src/main/resources/db/migration/V1__create_comments.sql`: comments schema and appropriate article/time lookup indexes.

Use existing Spring Web client facilities with configured timeouts rather than adding an HTTP library unnecessarily. Include only required dependencies at the versions used by the root build; DGS is not needed in the comments service. Scope the root build's recursive Spotless target so the independent service owns its own formatting checks, including exclusions for its generated code. Keep optional local sample-comment fixtures separate from live-data import, using only relevant comment rows with IDs matching monolith development fixtures.

## 4. Contracts and compatibility

### Public REST: unchanged at the monolith boundary

| Method and route | Expected behavior |
| --- | --- |
| `GET /articles/{slug}/comments` | Anonymous access supported; `200` with `{"comments": [...]}`. |
| `POST /articles/{slug}/comments` | Authenticated; body `{"comment":{"body":"..."}}`; `201` with `{"comment": {...}}`. |
| `DELETE /articles/{slug}/comments/{id}` | Authenticated comment author or article author; `204` with no body. |

Preserve comment fields, author profile fields including viewer-specific `following`, UTC timestamp formatting, and the existing error envelope. Characterize and preserve `401` for unauthenticated writes, `403` for forbidden deletion, `404` for missing/wrong-article resources, and `422` for REST body validation. Do not expose internal IDs or diagnostic details through new public fields.

The current `CommentData` hides `articleId` with `@JsonIgnore`. Keep it as a monolith view model, not the wire type. Define a separate service-local internal DTO carrying both `articleId` and `authorId`; blindly serializing the current DTO will break GraphQL composition. For paged responses, use a `comments` array plus a `pageInfo` object containing `hasNextPage`, `hasPreviousPage`, `startCursor`, and `endCursor`, then adapt that to the existing GraphQL connection.

### Private REST: proposed contracts to finalize in phase 1

Use an `/internal` prefix, existing article/comment URL patterns, and named JSON envelopes. These routes are proposals, not existing endpoints.

| Owner | Endpoint | Minimal contract |
| --- | --- | --- |
| Comments | `POST /internal/articles/{slug}/comments` | Existing comment-body envelope; trusted end-user authentication forwarded separately; returns a comment DTO with internal article/author IDs and public profile data. |
| Comments | `GET /internal/articles/{slug}/comments` | Unpaged REST-compatible list by default. With validated `first/after` or `last/before`, returns comments plus explicit page metadata for GraphQL. |
| Comments | `DELETE /internal/articles/{slug}/comments/{id}` | Applies article-scoped lookup and ownership policy; `204` on success. |
| Monolith | `GET /internal/articles/{slug}` | `{"article":{"id":"...","authorId":"..."}}`; authoritative existence and owner lookup. |
| Monolith | `GET /internal/user` | Validates forwarded end-user token and current account existence; returns only `{"user":{"id":"..."}}`. |
| Monolith | `POST /internal/profiles/batch` | Bounded set of author IDs; returns `{"profiles":[...]}` with IDs, public profile fields, and viewer-specific following state. Viewer comes from validated authentication, never an arbitrary request field. |

Use independent DTO definitions on each side, checked by contract tests; do not share a compiled DTO/entity module. Version incompatible internal changes explicitly and deploy additive contract changes before consumers.

For a list, batch unique author IDs instead of one request per comment. Split large unpaged lists into bounded batches without silently truncating the public response. Preload identity, article metadata, and the author profile before a create write where practical, so an avoidable enrichment failure does not happen after persistence.

### Authentication and trust

- Keep public JWT issuance and account lookup in the monolith for this extraction. Do not distribute its signing secret to the new service.
- Require authenticated service transport in both directions. Proposed production baseline: mutually authenticated TLS, with an allowlisted service identity per route; deployment owners must approve certificate provisioning before rollout.
- Forward the original end-user `Authorization` token over that transport when present. The comments service validates identity via the internal monolith endpoint and uses only the returned ID for ownership checks. No user token is required for anonymous reads, but service authentication is always required.
- Reject spoofed user-ID headers and unauthorized internal callers. Add explicit application-level Spring Security rules for `/internal/**` in both applications, ordered before public/catch-all rules, requiring the verified allowlisted service certificate identity independently of end-user authentication. The existing `anyRequest().authenticated()` rule is insufficient: a normal user JWT must never authorize an internal caller. Terminate mTLS in the application for the baseline rather than trusting a caller-supplied identity header. Internal paths must not inherit the public `/articles/**` anonymous rule or be exposed by the public ingress.
- Treat dependency unavailability as an availability error, not anonymous identity or successful authorization. Characterize invalid-token behavior on anonymous reads separately; never authorize a write with missing/invalid identity.
- Store certificates and credentials outside source control. Do not log tokens, comment bodies, or profile payloads.

### GraphQL remains a monolith adapter

Keep `addComment`, `deleteComment`, `Article.comments`, `Comment.author`, and `Comment.article`, including existing payload types and selection behavior. Translate service errors through the existing GraphQL error conventions rather than treating HTTP statuses as GraphQL responses.

Adapt `CommentMutation`, `CommentDatafetcher`, `ProfileDatafetcher`, and the comment resolver in `ArticleDatafetcher` together. Use one documented comment-context shape (for example, a map keyed by comment ID), retaining article IDs and profile data. Reuse the returned profile in `Comment.author` rather than adding a new per-comment remote lookup. Resolve `Comment.article` locally in the monolith using the retained article ID and preserve the article resolver's own local context.

Add regression coverage for both mutation payloads and article comment connections with nested `author` and `article` selections. The current `getCommentArticle` assumes a `CommentData` context while producers build maps; reproduce this suspected defect before deciding a separate, explicit fix.

### Paging and timestamp risks

The existing comment cursor SQL has timestamp-only comparisons, no SQL limit, and the query service removes only one extra row. Equal timestamps and over-limit results need characterization tests; do not mistake present behavior for a sound pagination specification. The new cursor query must fetch at most `limit + 1` rows and return at most `limit` comments, with the extra row used only for page metadata. Define forward/backward order, malformed cursor handling, missing/conflicting `first` and `last` arguments, and tie behavior before remote paging is enabled. A cursor-format change (such as adding an ID tie-breaker) requires an explicit compatibility decision and old-cursor handling, not a silent extraction change.

Preserve stored `created_at` and `updated_at` during migration. Existing REST mapping and GraphQL rendering both expose creation time as update time; preserve that wire behavior unless a separately approved fix changes it.

## 5. Failures, consistency, and lifecycle

- Configure connect/read deadlines on every client; total nested-call time must fit within the incoming request budget. Bound concurrency and propagate correlation IDs.
- Map timeouts, connection failures, and upstream 5xx to a clear unavailable response (`503` at REST; the established GraphQL error shape). Never return an empty list for a failed comment/profile lookup or convert infrastructure failure to `404`.
- Do not automatically retry create requests. A response can be lost after a committed write, so the result may be unknown; document this behavior and test it. If transparent retries are later required, first add durable idempotency keys with atomic result recording. Delete retries also need deliberate response semantics.
- Fail closed for article-owner or identity lookup failures. Missing profiles/orphan data must be reported, not silently fabricated; investigate pre-existing orphans before cutover.
- Keep each comment write in a local transaction. Do not hold a database transaction open across HTTP calls.
- Article existence is checked before a write, but the article can be deleted immediately afterwards. Initial extraction accepts this pre-existing check/write race and preserves inaccessible orphan comments rather than promising cross-service atomicity.
- Article deletion currently deletes only the article row in `ArticleMapper.xml`; no comment cascade is implemented there. Do not introduce a cascade as an undocumented extraction side effect. A future cleanup/event/tombstone policy is a separate decision.
- The service remains dependent on monolith identity/article/profile availability. Local public-profile projections and article lifecycle events can reduce that dependence later; they are not prerequisites for this first extraction.

## 6. Implementation phases and exit gates

### Phase 0: characterize behavior

1. Run the baseline root tests/build with the allowed coverage-verification exclusion and record unrelated failures without changing thresholds.
2. Expand REST cases for anonymous reads, authentication, validation, missing articles, wrong-article comment IDs, both permitted deleters, and forbidden deletion.
3. Add GraphQL cases for mutations, nested fields, both cursor directions, invalid arguments, equal timestamps, and more than one extra page row.
4. Reproduce the context/paging issues above and handle required fixes in separately identifiable changes. Do not promise compatibility with an untested behavior.

Exit: public contract fixtures and an explicit decision on known defects/paging semantics are reviewed. Reproduced failures of nested `Comment.article` resolution and page-size bounds must be fixed in separately identifiable, approved changes with passing regressions before the remote path is considered compatible; they cannot be deferred past cutover.

### Phase 1: establish application and wire boundaries

1. Introduce comment commands behind an application facade used by both REST and GraphQL instead of direct repository writes in adapters.
2. Express authorization inputs as authenticated user ID, comment owner ID, and article owner ID.
3. Add local article/profile lookup ports, then implement protected internal monolith contracts using local data only.
4. Finalize DTOs, error translations, service-authentication deployment, limits, and paging contracts; add producer/consumer tests.

Exit: existing paths still use local comments; new internal routes enforce service identity and cannot recurse into comments.

### Phase 2: build the independent service

1. Add the independent Gradle project and comments-only Flyway schema.
2. Move/adapt comment domain, repository, mapper, read-port implementation, command/query logic, and local cursor utilities. Retain the monolith implementation temporarily for pre-cutover operation.
3. Replace user joins, shared result maps, and cross-domain objects with `ArticleServiceClient` / `UserServiceClient` and local DTOs.
4. Implement authenticated internal REST endpoints and ownership enforcement inside the service, not only at the facade.
5. Add unit, database, API, client-failure, and authentication tests. Verify startup/migrations against a fresh comments database.

Exit: service builds and runs without monolith source/classes or database access; dependencies can be stubbed for isolated tests.

### Phase 3: integrate compatibility adapters

1. Add `CommentsServiceClient` in the monolith and proposed `comments.backend=local|remote` plus `comments.mutations-enabled` configuration applied to all REST and GraphQL comment operations. Resolve mode from one deployment configuration, not per-request or independent read/write flags. Keep local mode as the pre-cutover default; a service outage must not switch modes.
2. Translate internal DTOs to existing external shapes and update all GraphQL context consumers together.
3. Exercise the two running processes against separate databases. Shadow only read traffic on a consistent copy; never mirror writes.
4. Instrument latency, error rates, denied writes, dependency failures, and comment counts without sensitive payload logging.

Exit: contract and failure tests pass through both public protocols; no hidden local-comment query remains on the remote path.

### Phase 4: migrate and cut over

Use a short, approved maintenance window rather than dual writes or a broker for this first extraction.

1. Obtain explicit operator approval for the migration commands and backups. Audit orphan references, schema versions, row counts, and timestamp formats.
2. Back up the monolith SQLite database using a consistent SQLite backup procedure. Account for WAL state; do not copy only a live database file and assume consistency.
3. Pause comment mutations through BOTH REST and GraphQL, drain in-flight writes on every instance, and pause article deletion for the final transfer/validation window.
4. Initialize a fresh comments database via Flyway. Import only comment rows, preserving IDs, body, author/article IDs, and both timestamps. Keep live import separate from development fixtures; make reruns fail safely on conflicting IDs instead of overwriting data.
5. Validate exact row counts, IDs, field equality/checksums, representative article reads, owner checks, and orphan reports while the source remains frozen.
6. Change all instances to remote mode for reads and writes as one coordinated cutover while mutations remain paused. Drain and stop the old deployment, then start the new deployment with remote mode and mutations still disabled; do not perform an overlapping rolling release with old local writers. Verify the reported backend configuration on every instance before enabling mutations. Keep the comments service's mutation gate closed during import and open it only once this writer-fencing check passes.
7. Run REST/GraphQL smoke checks, resume mutations, and monitor. The comments service is now the sole writer. Retain the source table and backup read-only for rollback.

Do not edit historical monolith Flyway migrations or use `repair` to conceal checksum differences. Fresh monolith databases may still contain unused historical comment fixtures until a later forward-only cleanup migration. Do not drop/truncate tables in this extraction rollout.

Important: the current root `bootRun` and `clean` tasks delete `dev.db`. Do not use them on a database containing migration data. Build without `clean` and launch the packaged application against explicit database paths; the new service must not copy these destructive hooks. Any later change to existing hooks should be explicit and separately reviewed.

Exit: validated migration, a single active comment writer, both API styles on remote mode, and an approved rollback checkpoint.

### Phase 5: retire the local implementation

After an agreed observation/rollback window, remove unused monolith comment repositories, mapper registrations, write rules, and local query paths in a separate PR. Keep only adapter DTOs and client code needed for REST/GraphQL. Preserve shared article/profile utilities still used by other capabilities. Retire the local/remote switch once rollback no longer depends on it.

Any eventual removal of retained comment data requires a separately approved forward migration and backup/retention decision.

## 7. Rollback

- Before accepting remote writes: keep mutations paused, restore the all-instance local routing configuration, verify the untouched source, and resume.
- After accepting remote writes: switching a flag back would lose new comments and resurrect deleted ones. Pause mutations, drain both paths, back up both databases, and reconcile a full authoritative comments snapshot back to the monolith, including deletions. Reconciliation is a destructive data operation and requires explicit approval and a rehearsed procedure. Validate field equality before switching every instance back and resuming.
- If reconciliation has not been rehearsed or cannot be completed safely, keep the comments service authoritative and roll forward; do not fall back automatically to stale local data on an outage.
- Rehearse both rollback cases using disposable databases before a live migration.

## 8. Verification plan

Existing starting points:

- [`CommentsApiTest`](../src/test/java/io/spring/api/CommentsApiTest.java)
- [`CommentQueryServiceTest`](../src/test/java/io/spring/application/comment/CommentQueryServiceTest.java)
- [`MyBatisCommentRepositoryTest`](../src/test/java/io/spring/infrastructure/comment/MyBatisCommentRepositoryTest.java)

Implementation verification commands (run from the repository root once the service exists):

```sh
./gradlew build -x jacocoTestCoverageVerification
./comments-service/gradlew -p comments-service build -x jacocoTestCoverageVerification
```

Keep tests, formatting checks, and compilation enabled. Coverage thresholds remain unchanged; record the exclusion as required by repository guidance. For the first baseline, a focused command is:

```sh
./gradlew test --tests 'io.spring.api.CommentsApiTest' --tests 'io.spring.application.comment.CommentQueryServiceTest' --tests 'io.spring.infrastructure.comment.MyBatisCommentRepositoryTest' -x jacocoTestCoverageVerification
```

Acceptance matrix:

| Area | Required evidence |
| --- | --- |
| REST compatibility | Statuses, envelopes, author/follow state, anonymous access, validation, timestamps. |
| GraphQL compatibility | Unchanged schema, mutations, nested author/article, context shape, cursor metadata and boundary cases. |
| Authorization | Both permitted deleters; wrong-article IDs; spoofed identity; missing/invalid token; untrusted service caller. |
| Data isolation | Separate SQLite files; comments SQL has no joins/references to monolith tables; no monolith artifact dependency. |
| Failure behavior | Service and callback outages; deadlines; no silent empty responses or stale fallback; post-commit response loss. |
| Migration | Exact field preservation, duplicates/conflicts, orphan audit, all-instance writer fencing, rollback before/after remote writes. |
| Cross-feature regression | Article CRUD, users/profiles/follows, favorites, and tags still work in the monolith. |
| Performance | Batched profile calls; bounded connection pools; no per-comment remote lookup; compare representative page latency to baseline. |

This documentation-only PR needs source/contract review, link validation, and `git diff --check`; the implementation build and runtime gates above are future work, not results claimed by this plan.

## 9. Decisions requiring approval before implementation/cutover

1. Confirm the temporary facade-and-callback architecture and its availability trade-off.
2. Approve the service-to-service authentication/certificate deployment and private ingress rules without weakening existing policies.
3. Decide which reproduced GraphQL/pagination defects to fix separately, including cursor compatibility and tie handling.
4. Approve the maintenance window, writer fencing, backup locations, rollback rehearsal, and retention period.
5. Accept the initial article-delete race/orphan policy or scope a stronger lifecycle protocol separately.

Definition of done: the comments service owns all live comment persistence and business rules; both existing public protocols work through tested contracts; both applications build independently; data migration and rollback are rehearsed; and no public request silently falls back to the old comment table.
