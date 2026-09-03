# Phase 5 — User extraction

Status: **design, awaiting approval**. Starts only after Phase 4 is confirmed complete and this doc is approved. Highest-risk phase (authentication).

## 1. Scope

Extract Users/Profiles/Follows into `user-service` (port 8084, DB `user.db`).

Owned data: `users (id, username UNIQUE, password, email UNIQUE, bio, image)`, `follows (user_id, follow_id)`. Seed: users + follows.

### Monolith touch points

| Layer | Class / file | Usage |
|-------|--------------|-------|
| api | `UsersApi` (`POST /users`, `POST /users/login`), `CurrentUserApi` (`GET/PUT /user`), `ProfileApi` (`GET /profiles/{username}`, `POST/DELETE .../follow`) | `UserService`, `UserRepository`, `UserQueryService`, `ProfileQueryService`, `JwtService` |
| api/security | `JwtTokenFilter` -> `UserRepository.findById` on every authenticated request; `WebSecurityConfig` | hot path |
| graphql | `UserMutation`, `MeDatafetcher`, `ProfileDatafetcher`, `RelationMutation`, `SecurityUtil` | |
| application | `UserService` (register/update, BCrypt), `DuplicatedEmailValidator`, `DuplicatedUsernameValidator`, `UserQueryService`, `ProfileQueryService`, `ArticleQueryService`/`CommentQueryService` (`UserRelationshipQueryService.followingAuthors/isUserFollowing`, `followedUsers` for feed; `profileData` joins) | |
| infrastructure | `MyBatisUserRepository`, `UserMapper.xml`, `UserReadService.xml`, `UserRelationshipQueryService.xml`, `DefaultJwtService` | |
| core | `User`, `UserRepository`, `FollowRelation`, `JwtService` | |

## 2. Planned changes

### 2.1 `user-service`
- `V1__create_user_tables.sql` (`users`, `follows`) + seed.
- Internal API: `GET /internal/users/{id}`, `GET /internal/users/by-username/{username}`, `GET /internal/users/by-email/{email}`, `GET /internal/users?ids=` (batch profiles), `POST /internal/users` (register — BCrypt done in the service), `PUT /internal/users/{id}`, `POST /internal/users/{id}/credentials/verify` `{"password"}`, `GET /internal/users/{id}/following?ids=`, `GET /internal/users/{id}/followed`, `PUT/DELETE /internal/users/{id}/follows/{targetId}`.
- Token issuing: the **monolith keeps issuing and validating JWTs** (`DefaultJwtService`, same `jwt.secret`) in this phase; `user-service` validates the same tokens. Moving the issuer is a later, separate decision (open question).

### 2.2 Monolith seam
- `extraction.user.{enabled,read,write,base-url}` (read: monolith|extracted|shadow; write: monolith|extracted|dual-write — see `04-strangler-wiring-design.md` §1.1).
- `UserQueryPort` (`findById`, `findByUsername`, `findByEmail`, `profilesByIds`), `UserCommandPort`, `FollowPort` (`followingAuthors`, `isFollowing`, `followedUsers`, `follow`, `unfollow`).
- `JwtTokenFilter`: `UserRepository.findById` -> `UserQueryPort.findById` with a short-TTL in-process cache (e.g. Caffeine, 30 s) to keep the per-request cost bounded when routed remotely.
- `UserService.register/update`, duplicate validators, `ProfileQueryService`, `UserQueryService`, `ArticleQueryService`/`CommentQueryService` profile composition switch to the ports.
- `profileData` currently comes from SQL joins in `ArticleReadService.xml`/`CommentReadService.xml`; after Phase 4 articles are remote, so profile composition is already in-process; Phase 5 swaps its source.

## 3. Risks

| Risk | Mitigation |
|------|-----------|
| Auth on every request depends on a remote call | cache + fallback to local `users` table (kept authoritative until cutover); parallel-run of authenticated endpoints |
| Password hashes move between DBs | backfill copies `password` verbatim (BCrypt); verification endpoint compares in the service; never log |
| Username/email uniqueness enforced by DB UNIQUE in both stores during dual-write | local-first; remote conflict -> reconcile report (should not happen since same data) |
| Profile `following` flag semantics (`isUserFollowing`, `followingAuthors`) | golden `ProfileQueryServiceTest`, `ProfileApiTest`, `CurrentUserApiTest`, `UsersApiTest` |
| Registration response `UserWithToken` envelope | contract test pins the envelope; token still issued by the monolith |
| Selenium E2E (`src/test/java/io/spring/selenium`) logs in through the UI | run `seleniumTest` task against flag ON before cutover |

## 4. Validation
1. Golden: `UsersApiTest`, `CurrentUserApiTest`, `ProfileApiTest`, `ProfileQueryServiceTest`, `MyBatisUserRepositoryTest`, `DefaultJwtServiceTest`, `RealworldApplicationTests`, plus all article/comment tests (profile composition).
2. Contracts for every internal endpoint (esp. `credentials/verify`, batch profiles, following).
3. Edge cases: duplicate email/username (422 envelope), login with wrong password (422/401 as today), follow self, follow unknown username (404), profile of unknown username (404), anonymous profile read (`following=false`).
4. Parallel run for all user/profile endpoints and for one authenticated article request (token validation path).
5. Reconciliation on `users` by `id` and `follows` by pair.

## 5. Execution order
1. Service + V1 + endpoints + provider contracts. 2. Backfill users + follows. 3. Monolith ports (flag OFF), filter cache. 4. Dual-write, reconcile. 5. Reads ON (shadow, then live). 6. Cutover. 7. Decide on JWT issuer move (separate approval). 8. Decommission later.

## 6. Rollback
1. `extraction.user.enabled=false` -> `JwtTokenFilter` and all user reads/writes use the local `users`/`follows` tables (authoritative, dual-written). Existing JWTs remain valid because the issuer/secret never moved.
2. Post-cutover: reverse backfill users by `id` (upsert) and follows by pair; reconcile.
3. No FKs: `users` can be re-populated without affecting `articles.user_id`, `comments.user_id`, `article_favorites.user_id`.

## Open questions for the user
- Should the JWT issuer eventually move to `user-service`, or stay in the façade permanently?
- Should the strangler façade (monolith) remain as the single public entry point / API gateway after Phase 5, or should services be exposed directly?
