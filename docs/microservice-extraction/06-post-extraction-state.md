# 06 — Post-extraction state (end of Phase 5)

What the system looks like once Phases 1–5 are implemented and every domain has been cut over
(state C of [`05-data-sync-and-rollback-design.md`](05-data-sync-and-rollback-design.md) §5).
This is the target picture; each phase's own doc under [`phases/`](phases/) and
[`runbooks/`](runbooks/) remains the authority for how to get there and how to get back. Nothing
here is a new decision — except the two open questions at the end, which are deliberately left to
the user.

## 1. Topology

```
                     ┌──────────────────────────────────────────────────────────────┐
  browser / Next.js  │  Conduit monolith  :8080  (strangler façade)                 │
  ──── REST ───────► │  io.spring.api.*      REST  /users /user /profiles /articles │
  ──── GraphQL ────► │  io.spring.graphql.*  DGS   /graphql                          │
                     │  DefaultJwtService    issues + validates HS512 (jwt.secret)  │
                     │  JwtTokenFilter       Token <jwt> -> sub -> UserQueryPort     │
                     │  *QueryService        composes author/profile/tagList/        │
                     │                       favorited/favoritesCount per response   │
                     │  io.spring.infrastructure.extraction.{favorite,comment,      │
                     │                       tag,article,user}  Routing ports        │
                     │  dev.db  (replica of every domain table until dropped)        │
                     └───────┬──────────────┬──────────────┬──────────────┬─────────┘
                             │ /internal/*  │              │              │   (same JWT_SECRET,
                             ▼              ▼              ▼              ▼    `Token <jwt>` propagated)
                    favorite-service  comment-service  article-service   user-service
                    :8081             :8082            :8083             :8084
                    favorite.db       comment.db       article.db        user.db
                    article_favorites comments         tags,             users,
                                                       article_tags,     follows
                                                       articles
```

The monolith stays the **only public entry point**. Services expose `/internal/**` (plus
`/actuator/health`) and are reached only by the façade, with the caller's `Authorization: Token
<jwt>` forwarded where the endpoint needs the subject (04 §4).

## 2. Services

| Service | Port | DB file | Tables (Flyway V1) | Seed | Phase / design doc | Internal API (summary) |
|---|---|---|---|---|---|---|
| `favorite-service/` | 8081 | `favorite.db` | `article_favorites(article_id, user_id)` | `V2__seed_favorites.sql` | 1 — [`phases/phase-1-favorite.md`](phases/phase-1-favorite.md) | `POST /internal/favorites/counts`, `POST /internal/favorites/query` (batch favorited/counts), `GET /internal/favorites/by-user/{userId}/article-ids`, `PUT/DELETE /internal/favorites/{articleId}/{userId}` |
| `comment-service/` | 8082 | `comment.db` | `comments(id, body, article_id, user_id, created_at, updated_at)` | `V2__seed_comments.sql` | 2 — [`phases/phase-2-comment.md`](phases/phase-2-comment.md) | `GET /internal/articles/{articleId}/comments[/cursor]`, `GET /internal/comments/{id}`, `POST /internal/articles/{articleId}/comments`, `DELETE /internal/articles/{articleId}/comments/{id}` |
| `article-service/` | 8083 | `article.db` | `tags(id, name)`, `article_tags(article_id, tag_id)` (Phase 3); `articles(id, user_id, slug, title, description, body, created_at, updated_at)` (Phase 4) | `V2__seed_tags.sql`, `V3__seed_articles.sql` | 3 — [`phases/phase-3-tag.md`](phases/phase-3-tag.md); 4 — [`phases/phase-4-article.md`](phases/phase-4-article.md) | `GET /internal/tags`, `GET /internal/articles/tags`, `GET /internal/tags/{name}/article-ids`, `PUT /internal/articles/{articleId}/tags`; `GET /internal/articles/{id}`, `GET .../by-slug/{slug}`, `GET /internal/articles/ids[/cursor]`, `GET /internal/articles/feed[/cursor]`, `POST /internal/articles`, `PUT/DELETE /internal/articles/{id}` |
| `user-service/` | 8084 | `user.db` | `users(id, username UNIQUE, password, email UNIQUE, bio, image)`, `follows(user_id, follow_id)` | `V2__seed_users.sql` | 5 — [`phases/phase-5-user.md`](phases/phase-5-user.md) §2.1 (canonical table) | `GET /internal/users/{id}`, `.../by-username/{u}`, `.../by-email/{e}`, `GET /internal/users?ids=`, `POST /internal/users` (hash supplied by the façade), `PUT /internal/users/{id}`, `POST /internal/users/{id}/credentials/verify`, `GET /internal/users/{id}/following?ids=`, `GET .../followed`, `GET/PUT/DELETE /internal/users/{id}/follows/{targetId}` |

Every service is an independent Gradle project (Spring Boot 2.6.3, Java 11, MyBatis XML, SQLite,
Flyway, Spotless, JWT filter with the shared secret, `{"errors":{...}}` envelope, provider
Spring Cloud Contract tests, in-memory SQLite `application-test.properties`), laid out as
`io.spring.<domain>.{api,core,application,infrastructure}` per
[`templates/03-extracted-service-template.md`](templates/03-extracted-service-template.md) and
AGENTS.md. No service talks to another service; all cross-domain composition happens in the façade.

Schema invariant kept everywhere: **no foreign keys, no cascades**. `articles.user_id`,
`comments.{article_id,user_id}`, `article_favorites.{article_id,user_id}`, `article_tags.*`,
`follows.*` reference ids by value across database files; ids are generated by the façade before a
dual-write so every store holds the same value.

## 3. The monolith as strangler façade

What the monolith still owns after Phase 5 — none of it moved, by design:

| Responsibility | Where | Notes |
|---|---|---|
| Public REST API (`/users`, `/users/login`, `/user`, `/profiles/**`, `/articles/**`, `/tags`) | `io.spring.api.*` | envelopes byte-identical to the pre-extraction baseline (`00-golden-test-baseline.md`, parallel-run harness `02-parallel-run-harness.md`) |
| Public GraphQL API (`/graphql`, Netflix DGS) | `io.spring.graphql.*` | datafetchers call the same application services / ports as REST |
| **JWT issuer and validator** | `DefaultJwtService` (HS512, `jwt.secret`, `sub` = user id, `jwt.sessionTime`) | tokens are issued on `POST /users` and `POST /users/login` by the façade only; `user-service` validates the same tokens but never issues one. Consequence: no flag flip, cutover or rollback in any runbook invalidates a token |
| **Password hashing** | `UserService` + `PasswordEncoder` (BCrypt) | hash computed in the façade, sent to `user-service` on register/update; login in extracted mode = `findByEmail` + `POST /internal/users/{id}/credentials/verify`, fallback to local `passwordEncoder.matches` |
| Authentication filter | `JwtTokenFilter` -> `UserQueryPort.findById` | 30 s in-process cache when routed remotely; falls back to the local `users` replica on remote failure; invalid/unknown token -> anonymous |
| Authorization | `AuthorizationService` | compares `user.getId()` with `article.getUserId()` / `comment.getUserId()` — ids only |
| Validation | `DuplicatedEmailValidator`, `DuplicatedUsernameValidator`, `UpdateUserValidator`, article title/slug uniqueness, `@Valid` bodies | run local-first against the ports |
| Response composition | `ArticleQueryService`, `CommentQueryService`, `ProfileQueryService`, `UserQueryService`, GraphQL datafetchers | `author`/`profileData` (User port), `following` (Follow port), `tagList` (Tag port), `favorited`/`favoritesCount` (Favorite port), feed = `followedUsers` (User) -> `GET /internal/articles/feed` (Article) |
| Routing seams | `io.spring.infrastructure.extraction.<domain>.*`: `Remote<X>` adapter + `<X>ServiceClient` + DTOs, `Routing<X>QueryPort` (monolith / extracted / shadow, fallback), `Local`/`Remote`/`DualWrite<X>CommandPort`, `ExtractionProperties`/`ExtractionConfig` | 04 §1–§3; one seam per domain, identical shape |
| Replica tables in `dev.db` | all domain tables, dual-written until each phase's cutover, then kept warm by `reconcile --authoritative service --repair to-source` | dropped per domain only after ≥ 30 days without rollback via a new Flyway migration (separate approval each) |
| Next.js frontend, Selenium E2E, golden tests | unchanged | the frontend never learns about services |

## 4. Feature flags and defaults

All flags live in the monolith's `src/main/resources/application.properties`, are bound by
`ExtractionProperties` (04 §1.1) and are overridable per environment via Spring relaxed binding
(`EXTRACTION_<DOMAIN>_<KEY>`). **Every domain ships OFF**; the defaults below are the committed
values, i.e. a fresh checkout behaves exactly like the pre-extraction monolith.

| Key (per `<domain>` ∈ `favorite`, `comment`, `tag`, `article`, `user`) | Default | Values | Meaning |
|---|---|---|---|
| `extraction.<domain>.enabled` | `false` | `true` / `false` | master switch; `false` ⇒ every read and write is local regardless of the other keys |
| `extraction.<domain>.read` | `monolith` | `monolith` / `extracted` / `shadow` | `shadow`: local result returned, remote called asynchronously and diffed (`extraction.shadow.mismatch`) |
| `extraction.<domain>.write` | `monolith` | `monolith` / `dual-write` / `extracted` | `dual-write`: local first, remote mirrored (failures -> WARN + outbox, never a user-visible error) |
| `extraction.<domain>.base-url` | `http://localhost:8081` (favorite), `:8082` (comment), `:8083` (tag **and** article — same service), `:8084` (user) | URL | service base URL |
| `extraction.<domain>.connect-timeout` | `500ms` | duration | HTTP client |
| `extraction.<domain>.read-timeout` | `1500ms` | duration | HTTP client |
| `extraction.<domain>.fallback` | `monolith` | `monolith` / `empty` / `fail` | behaviour when `read=extracted` and the remote call fails (04 §3.3) |

Flags are independent per domain and may be in different states at the same time (the runbooks
list the few ordering constraints: Tag before Article for `tagList` shadow comparison; any Article
state is fine for User, but the User read flip changes the source of every `author` block). On
the current integration branch Phases 1–4 declare all seven keys and Phase 5 has
`extraction.user.enabled=false` committed as the first of its seven; the remaining `user` keys are
added with the Phase 5 seam and take the same defaults, with `base-url=http://localhost:8084`.

State machine per domain (05 §5), identical for all five:

| State | `.enabled` | `.write` | `.read` | Authority |
|---|---|---|---|---|
| Off | `false` | – | – | monolith |
| A. Shadow-write | `true` | `dual-write` | `monolith` | monolith |
| A'. Shadow-read | `true` | `dual-write` | `shadow` | monolith |
| B. Parallel read | `true` | `dual-write` | `extracted` | monolith |
| C. Service authoritative | `true` | `extracted` | `extracted` | service |

Rollback from A/A'/B is one flag (`enabled=false`), from C it is the flag plus the domain's
`reverse-backfill` + `reconcile --authoritative service` (runbooks §7).

## 5. Sync tooling — `tools/favorite-sync`

One CLI, five `--domain` values, three commands (`backfill`, `reverse-backfill`, `reconcile
[--repair to-target|to-source] [--delete-extras] [--authoritative] [--max-repair]`), one exit-code
contract (0 clean, 1 drift/conflicts remain, 2 error). Details in
[`tools/favorite-sync/README.md`](../../tools/favorite-sync/README.md).

| `--domain` | `--target` | Tables (in processing order) | Key | Compared payload | Unique-conflict columns | Duplicate-pair reporting |
|---|---|---|---|---|---|---|
| `favorite` (default) | `favorite.db` | `article_favorites` | `(article_id, user_id)` | — | — | — |
| `comment` | `comment-service/comment.db` | `comments` | `id` | `body, article_id, user_id, created_at, updated_at` | — | — |
| `tag` | `article-service/article.db` | `tags`, then `article_tags` | `id` / `(article_id, tag_id)` | `name` / — | — | `article_tags` |
| `article` | `article-service/article.db` | `articles` | `id` | `user_id, slug, title, description, body, created_at, updated_at` | `slug` | — |
| `user` | `user-service/user.db` | `users`, then `follows` | `id` / `(user_id, follow_id)` | `username, password, email, bio, image` (hash compared as stored, never printed — `<redacted>` in any conflict value) / — | `username`, `email` | `follows` |

Every domain shares the same mechanics: T0 + online-backup snapshot, keyset-paginated chunks
committed one transaction at a time (restartable), `INSERT OR IGNORE` / `insert … where not
exists`, merge-join diff with `missingInService` / `extraInService` / `diverged` /
`duplicateKeysIn*` / `uniqueConflictsIn*` buckets, 1000-entry truncation with full totals, checksums,
`--max-repair` guard evaluated over the whole run. The JSON report always has
`{"domain", "authoritative", "tables":[{"table", ...}], "summary"}`.

Per-domain runbooks: [`phase-1-favorite-cutover.md`](runbooks/phase-1-favorite-cutover.md),
[`phase-2-comment-cutover.md`](runbooks/phase-2-comment-cutover.md),
[`phase-3-tag-cutover.md`](runbooks/phase-3-tag-cutover.md),
[`phase-4-article-cutover.md`](runbooks/phase-4-article-cutover.md),
[`phase-5-user-cutover.md`](runbooks/phase-5-user-cutover.md).

## 6. Verification surface that stays green

- Monolith: `./gradlew build -x jacocoTestCoverageVerification` (golden baseline of
  `00-golden-test-baseline.md`, extraction mock-server tests, parallel-run goldens, contract
  consumers). The JaCoCo 80 % gate is pre-existing and is never "fixed" (AGENTS.md).
- Each service: `./gradlew build` in its directory (unit + provider contract tests).
- Sync tool: `cd tools/favorite-sync && ./gradlew build installDist`.
- Spotless (google-java-format) needs a JDK 11; the root Spotless excludes `favorite-service/**`,
  `comment-service/**`, `article-service/**`, `tools/**` (and `user-service/**` once it lands) —
  each project formats itself.

## 7. What is *not* done at the end of Phase 5

- No monolith table has been dropped; `dev.db` still holds every domain table as a dual-written /
  reverse-repaired replica. Each drop is a separate Flyway migration and approval, ≥ 30 days after
  the domain's cutover.
- The outbox / retry for failed mirrors (05 §2.3) and the optional runtime flag-flip endpoint
  (04 §1.3 b) are as delivered by the phases; nothing in this document assumes more.
- Services still trust the façade's JWT with the shared `jwt.secret`; there is no service-to-service
  auth because there are no service-to-service calls.
- The frontend (`frontend/`) is untouched and points at `:8080` only.

## 8. Open program questions — left for the user

Both come from [`phases/phase-5-user.md`](phases/phase-5-user.md) "Open questions for the user"
and were **not** decided by any implementation slice. The current implementation takes the
conservative reading of each (issuer stays; façade stays) so that either answer remains possible.

1. **Should the JWT issuer eventually move to `user-service`, or stay in the façade permanently?**
   Today `DefaultJwtService` (issue + validate, `jwt.secret`, `sub` = user id) and BCrypt hashing
   live in the monolith; `user-service` stores hashes verbatim, verifies credentials on request and
   validates tokens with the same secret. Moving the issuer would (a) move `POST /users/login` /
   `POST /users` token minting and the password encoder into the service, (b) require a key
   rotation or dual-secret window so tokens issued before the move stay valid, and (c) turn the
   `JwtTokenFilter` cache/fallback from "resolve `sub` locally if the service is down" into "cannot
   validate at all if the service is down" unless validation stays local (public-key or shared
   secret). None of this is required for the extraction to be complete; the phase-5 runbook's
   rollback guarantee ("no token is ever invalidated") depends on the issuer *not* moving.

2. **Should the strangler façade (monolith) remain the single public entry point / API gateway
   after Phase 5, or should services be exposed directly?** Today every public contract — REST
   envelopes, GraphQL schema, error format, auth propagation, cross-domain composition
   (`author`, `following`, `tagList`, `favorited`, `favoritesCount`, feed) — is implemented once,
   in the façade, and pinned by the golden tests and the parallel-run harness. Exposing services
   directly would need a gateway (routing, auth, composition — e.g. a BFF or GraphQL federation),
   per-service public contracts and versioning, and would end the "envelopes byte-identical to the
   monolith" invariant that every phase has been verified against. Keeping the façade means the
   monolith remains a deployable (thin) application indefinitely, with `dev.db` reduced to nothing
   once the replicas are dropped.

Until these are answered the recommended end state is exactly §1–§5 above: five flags at state C,
replicas kept warm, façade unchanged, issuer unchanged.
