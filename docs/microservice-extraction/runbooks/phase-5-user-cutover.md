# Runbook — Phase 5 User cutover and rollback

Operator procedure for moving `users` and `follows` from the monolith (`dev.db`) to `user-service`
(`user-service/user.db`, port 8084). It follows [`phases/phase-5-user.md`](../phases/phase-5-user.md)
§2.1/§2.2/§4/§6 and [`05-data-sync-and-rollback-design.md`](../05-data-sync-and-rollback-design.md)
§3–§6, using the flag names of [`04-strangler-wiring-design.md`](../04-strangler-wiring-design.md)
§1.1 and the `favorite-sync` CLI with `--domain user` (see
[`tools/favorite-sync`](../../../tools/favorite-sync/README.md)). The structure mirrors
[`phase-4-article-cutover.md`](phase-4-article-cutover.md); differences specific to User are marked
**User:**.

**This is the authentication phase.** Every authenticated request passes through `JwtTokenFilter`,
which resolves the token subject to a `User` row. Two facts keep the blast radius bounded and are
assumed everywhere below (phase-5 §2.1, §6):

- **The JWT issuer and `jwt.secret` do not move.** `DefaultJwtService` in the monolith keeps issuing
  and validating tokens in every state of this runbook; `user-service` only *validates* the same
  HS512 tokens on its own protected endpoints. A token issued in state Off is valid in state C and
  a token issued in state C is valid after a rollback to Off — no user is logged out by any flag
  flip in this document.
- **Password hashing stays in the monolith.** The façade BCrypt-encodes on `POST /users` /
  `PUT /user` and sends the *hash* to `POST/PUT /internal/users`; the service stores it verbatim.
  Dual-write therefore produces byte-identical `users` rows on both sides and the backfill is a
  straight copy. The service never returns a hash; in extracted-read mode login uses
  `POST /internal/users/{id}/credentials/verify`.

What differs from Article (05 §1.2, §3):

- **Two tables, one domain, one invocation.** `--domain user` processes `users` (keyed by `id`,
  payload `username, password, email, bio, image` compared value-for-value **as stored**) first
  and `follows` (keyed by the pair `(user_id, follow_id)`, no PK, no unique index,
  insert-only-if-pair-absent) second, like `--domain tag`. One command, one JSON report with two
  `tables` entries, one exit code.
- **Two UNIQUE columns.** `username` and `email` are UNIQUE independently of the `id` key. A source
  row whose username *or* email is already held by a *different* id on the target is reported as a
  **conflict** (`uniqueConflictsInService` / `uniqueConflictsInMonolith`, one entry per clashing
  column, log line `backfill conflict table=users ...`), skipped, and counted as drift until an
  operator resolves it **by id** (phase-5 §6.2). The tool never overwrites the holder and never
  crashes on the constraint.
- **Hashes are never printed.** `diverged` entries list only the column name
  (`"columns":["password"]`); no log line or report field ever carries a `users.password` value.
  Do not `select password` from either DB in a ticket; compare `length(password)` or a
  `hex(sha256(...))` you compute yourself if you must.
- **`follows` may hold duplicate pairs.** `UserMapper.xml#saveRelation` has no guard against a
  double follow; the tool copies one row per distinct pair and reports the surplus as
  `duplicateKeysInMonolith`/`duplicateKeysInService` without counting it as drift — exactly like
  `article_tags` in Phase 3.
- **Nothing references `users` by FK.** `articles.user_id`, `comments.user_id`,
  `article_favorites.user_id`, `follows.*` reference user ids by value only, across four
  databases. Moving or re-populating `users` never changes an id and never invalidates them
  (phase-5 §6.3). `AuthorizationService` compares ids only.
- **Deleting a user does not exist** in Conduit (no endpoint). `--delete-extras` on `users`
  removes only rows created directly in the non-authoritative store; there is no cascade anywhere.

## 0. Prerequisites

| Item | Check |
|---|---|
| JDK 11 | `java -version` → 11.x (`export JAVA_HOME=...`) |
| CLI built | `cd tools/favorite-sync && ./gradlew installDist` → `build/install/favorite-sync/bin/favorite-sync`; below aliased as `favorite-sync` |
| Service schema | `user-service` has been started at least once against `user-service/user.db` so its Flyway `V1__create_user_tables.sql` created `users` and `follows` (the CLI refuses to run otherwise: `table 'users' is missing in user-service/user.db`) |
| Service healthy | `curl -s http://localhost:8084/actuator/health` → `{"status":"UP"}` |
| Shared JWT secret | monolith and service use the same `JWT_SECRET` — **User:** verify explicitly: a token issued by `POST /users/login` on the monolith must be accepted by `PUT /internal/users/{id}/follows/{targetId}` on the service (204), and a token signed with a different secret must be rejected (401) |
| Phases 1–4 state | any; the flags are independent. **User:** if Article is in state B/C, article/comment `author` composition already goes through `UserReadService` in-process, so the User read flip changes the source of every `author`/`profileData` block in the API — plan the shadow-read window (step 4) accordingly |
| Baseline | monolith `./gradlew build -x jacocoTestCoverageVerification` green, `extraction.user.enabled=false`; `seleniumTest` passes against the flag-OFF monolith |
| Reports dir | `mkdir -p build/reconcile/user` |
| Paths | run the CLI from the repo root so `dev.db` and `user-service/user.db` resolve as written below |

Flags are set as env vars (Spring relaxed binding) and take effect on (rolling) restart, e.g.
`EXTRACTION_USER_ENABLED=true EXTRACTION_USER_WRITE=dual-write`. If the optional runtime flip
endpoint (04 §1.3 b) is deployed, the same key/value pairs are POSTed instead.

State reference (05 §5):

| State | `extraction.user.enabled` | `.write` | `.read` | Authority | `JwtTokenFilter` reads |
|---|---|---|---|---|---|
| Off (today) | `false` | – | – | monolith | local `users` |
| A. Shadow-write | `true` | `dual-write` | `monolith` | monolith | local `users` |
| A'. Shadow-read | `true` | `dual-write` | `shadow` | monolith | local `users` (+ async remote diff) |
| B. Parallel read | `true` | `dual-write` | `extracted` | monolith | remote via 30 s cache, fallback local |
| C. Service authoritative | `true` | `extracted` | `extracted` | **service** | remote via 30 s cache, fallback local replica |

In every state the token itself is validated by the monolith's `DefaultJwtService` first; only the
`sub -> User` lookup changes source.

## 1. Enter state A — enable dual-write

```sh
EXTRACTION_USER_ENABLED=true \
EXTRACTION_USER_WRITE=dual-write \
EXTRACTION_USER_READ=monolith \
EXTRACTION_USER_BASE_URL=http://localhost:8084 \
EXTRACTION_USER_FALLBACK=monolith \
  <restart monolith>
```

Verify: register, update, follow and unfollow, and confirm both sides hold the same rows.

```sh
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"user":{"username":"dualwrite","email":"dualwrite@example.com","password":"p4ssw0rd"}}' \
  localhost:8080/users | jq '.user | {username, email, token}'
sqlite3 dev.db                 "select id, username, email, length(password), bio, image from users where username='dualwrite'"
sqlite3 user-service/user.db   "select id, username, email, length(password), bio, image from users where username='dualwrite'"
```

Expected: `201`, the `POST /internal/users` call visible in the service log with the *same* `id`
the monolith generated (phase-5 §2.1 — the caller generates the UUID and sends the BCrypt hash,
so the rows are identical), identical `length(password)` (60) on both sides, `bio=''` and the
default image on both. **Never print the `password` column itself.** Mirror failures must appear
only as WARN + outbox rows; the user request stays `201` and the token in the response is issued
by the monolith regardless.

Then, with `JWT` = the token from the response:

```sh
curl -s -X PUT -H "Authorization: Token $JWT" -H 'Content-Type: application/json' \
  -d '{"user":{"bio":"dual-write check"}}' localhost:8080/user | jq '.user | {username, bio}'
```

`bio` changes on both sides, `username`/`email`/`password` change on **neither** (`UserMapper.xml#update`
skips blank fields; `PUT /internal/users/{id}` must do the same — a `diverged` row with
`"columns":["password"]` after an update with no password is the first thing to look for if the
service re-hashes or blanks the column).

```sh
curl -s -X POST   -H "Authorization: Token $JWT" localhost:8080/profiles/<seed username>/follow | jq '.profile.following'   # true
sqlite3 dev.db               "select count(*) from follows where user_id='<id>'"      # 1
sqlite3 user-service/user.db "select count(*) from follows where user_id='<id>'"      # 1
curl -s -X POST   -H "Authorization: Token $JWT" localhost:8080/profiles/<seed username>/follow | jq '.profile.following'   # true, still 1 row on each side
curl -s -X DELETE -H "Authorization: Token $JWT" localhost:8080/profiles/<seed username>/follow | jq '.profile.following'   # false, 0 rows on each side
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "Authorization: Token $JWT" localhost:8080/profiles/<seed username>/follow   # 404 as today
```

Also register the same email a second time (`422 {"errors":{"email":["duplicated email"]}}`) and
the same username with a new email (`422 ... "username":["duplicated username"]`): the validators
run **local-first** in every state, so no `422` from the service should ever reach the user in
state A, and `user.db` must not gain a row for either attempt.

## 2. Record T0 and backfill (05 §3) — users, then follows, one command

Dual-write must already be on (step 1) so users and follows written after T0 arrive via the
mirror.

```sh
favorite-sync backfill --domain user --source dev.db --target user-service/user.db --chunk 5000 \
  | tee build/reconcile/user/backfill.log; echo exit=$?
```

Expected output (counts will differ):

```
backfill domain=user table=users source=dev.db target=user-service/user.db chunk=5000
backfill T0=2026-09-03T08:00:00.123Z
backfill snapshot=/tmp/favorite-sync-snapshot-....db
backfill sourceRows=5120 targetRowsBefore=3
backfill chunk=1 rows=5000 inserted=4997 skipped=3 lastKey=f3a1...
backfill chunk=2 rows=120 inserted=120 skipped=0 lastKey=ffe0...
backfill done rowsRead=5120 rowsInserted=5117 rowsSkipped=3 chunks=2 targetRowsAfter=5120 T0=2026-09-03T08:00:00.123Z table=users
backfill domain=user table=follows source=dev.db target=user-service/user.db chunk=5000
...
backfill done rowsRead=9310 rowsInserted=9308 rowsSkipped=2 chunks=2 targetRowsAfter=9310 T0=2026-09-03T08:00:00.123Z table=follows
backfill done domain=user tables=2 rowsRead=14430 rowsInserted=14425 rowsSkipped=5 chunks=4 T0=2026-09-03T08:00:00.123Z
exit=0
```

Record **T0** in the change ticket. `users` is keyset-paginated by `id`, `follows` by the pair.
`rowsSkipped > 0` is normal when dual-write already mirrored rows (the 3 + 2 above) or when the
job is re-run after an interruption; a crash mid-chunk rolls that chunk back and the next run
continues, across the `users`/`follows` boundary. `follows` rows that exist several times in
`dev.db` are inserted once (`insert ... where not exists`) — the surplus is reported by
`reconcile`, not copied.

**User:** if the output contains

```
backfill conflict table=users a1b2... email=jane@example.com held by c0de...
backfill conflict table=users a1b2... username=jane held by c0de...
backfill done rowsRead=5120 rowsInserted=5116 rowsSkipped=4 chunks=2 conflicts=2 targetRowsAfter=5119 T0=... table=users
exit=1
```

the service already holds that email/username under a different `id` (a row registered directly
against the service, a stale `user.db`, or a seed row that was edited on one side only). The
source row was **not** copied; one line is printed per clashing column, so one row can produce two
lines with the same or different holders. Decide by id which user is real:

- the service row is bogus → delete it (`sqlite3 user-service/user.db "delete from users where
  id='c0de...'"` with the service stopped, or `reconcile --repair to-target --delete-extras` in
  step 3) and re-run the backfill;
- the monolith row is bogus → delete it in `dev.db` the same way (its articles/comments/favorites/
  follows keep the id by value — this is what the data looked like already);
- both are real → one of them needs a new username/email, i.e. a `PUT /user` as that user through
  the monolith while in state A, then re-run.

Spot-check without exposing hashes:

```sh
sqlite3 dev.db               "select count(*), sum(length(password)=60) from users"
sqlite3 user-service/user.db "select count(*), sum(length(password)=60) from users"
sqlite3 dev.db               "select count(*), count(distinct user_id||'|'||follow_id) from follows"   # the gap = duplicate pairs
sqlite3 user-service/user.db "select count(*), count(distinct user_id||'|'||follow_id) from follows"   # distinct count identical to the monolith's
```

## 3. Reconcile to zero drift, N times (05 §4, §5.1)

Report-only run immediately after the backfill:

```sh
favorite-sync reconcile --domain user --source dev.db --target user-service/user.db \
  --report build/reconcile/user/$(date -u +%Y%m%dT%H%M%SZ).json; echo user=$?
```

Expected:

```
reconcile domain=user table=users   phase=before monolith=5120 service=5120 missing=0 extra=0 diverged=0 conflicts=0/0 status=CLEAN
reconcile domain=user table=follows phase=before monolith=9310 service=9308 missing=0 extra=0 diverged=0 duplicateKeys=2/0 status=CLEAN
reconcile report=build/reconcile/user/20260903T080500Z.json
user=0
```

The JSON report has `"domain":"user"` and two entries in `tables`, always `users` then `follows`.
`users` has the article-style `diverged` bucket plus `uniqueConflictsInService`/
`uniqueConflictsInMonolith`; `follows` has the tag-style `duplicateKeysInMonolith`/
`duplicateKeysInService`. `duplicateKeys=2/0` above is **not** drift (both sides hold the pair)
and is never repaired away.

- `exit=1` with `missing>0` on `users`: a registration after the snapshot whose mirror failed.
  Wait for the outbox to drain and re-run. If it persists, repair toward the monolith:
  ```sh
  favorite-sync reconcile --domain user --source dev.db --target user-service/user.db --repair to-target \
    --report build/reconcile/user/repair-$(date -u +%Y%m%dT%H%M%SZ).json
  ```
  Expected: `reconcile repair=to-target inserted=<n> deleted=0 updated=<m> ...`, then
  `phase=after ... status=CLEAN` for both tables, `exit=0`. The repair inserts `users` before
  `follows`.
- `missing>0` on `follows` only: a follow whose `PUT /internal/users/{id}/follows/{targetId}`
  mirror failed; same remedy.
- `extra>0`: rows only the service has — an unfollow whose `DELETE .../follows/{targetId}` mirror
  failed (on `follows`), or a user registered directly against the service (on `users`).
  Investigate the ids listed in the report; if the monolith is right, add `--delete-extras`. The
  tool **never deletes** without that flag.
- `diverged>0` on `users`: same `id`, different payload. The report lists the columns, e.g.
  `{"id":"...","columns":["bio","image"]}` (a `PUT /user` whose mirror failed — `PUT
  /internal/users/{id}` skips blank fields exactly like the monolith, so a partial mirror shows up
  here), `["email"]`/`["username"]` (same, and check the conflict buckets), or
  `["password"]` — the service stored a different hash. **User:** `["password"]` with no password
  change in between is a defect (the service re-hashed, trimmed or blanked the column); never
  inspect the values, `--repair to-target` overwrites the whole row from the monolith and the
  user's existing password keeps working because the monolith's hash wins. Record the pre-repair
  JSON first.
- **User:** `conflicts=n/m` (`uniqueConflictsInService`/`InMonolith`): a missing or diverged row
  cannot be written because another id holds its `username` or `email` on the repaired side. It is
  already counted in `missing`/`diverged`; the conflict entry names the `column` and the
  `conflictingId`; one row can appear twice (once per column). A repair skips such rows
  (`reconcile table=users skipped=N rows whose unique value is held by another key ...`) and exits
  `1`. If the holder is an `extraInService` row, `--repair to-target --delete-extras` removes it
  first and the repair converges in one pass; if the holder exists on both sides (a username or
  email *swap* between two accounts), resolve by id by hand as in step 2 — the tool never guesses.
- `exit=2` and `repair would touch N rows, above --max-repair`: mass drift — dual-write is broken.
  Nothing is written when the guard trips (it is evaluated over both tables). Do not override it;
  fix the mirror, then re-run backfill (step 2) and reconcile.

Schedule the report-only command hourly (cron/CI; exit code 1 = alert). The cutover criterion of
05 §5.2 is **N = 7 consecutive days** of `status=CLEAN` on both tables (minimum defensible N = 3).
Keep every JSON report — the one-line summaries are the audit trail, and none of them contain a
hash.

## 4. Enter state A' — `read=shadow`

Entry criteria (05 §5.1 items 1–2): step 3 clean, dual-write running ≥ 24 h with outbox lag ≈ 0.

```sh
EXTRACTION_USER_READ=shadow <restart monolith>
```

Reads are still served by the monolith's SQL; the remote adapter is called asynchronously and
diffed (04 §5). Monitor `extraction.shadow.mismatch` and the WARN diff logs on:

- `GET /profiles/{username}` (`findByUsername` + `isUserFollowing`), `GET /user` (`findById`),
  `POST /users/login` (`findByEmail` — the **password check stays local** in A'; only the row
  lookup is shadowed),
- the `author`/`profileData` composition of every article/comment endpoint and GraphQL
  datafetcher (`findByIds`, `followingAuthors`), and `GET /articles/feed` (`followedUsers`),
- **`JwtTokenFilter`**: `findById` on every authenticated request. In A' the filter still reads the
  local table synchronously; the shadow call exercises the remote path and the 30 s cache without
  being on the critical path. Watch the p99 of the shadow call — it is the latency you will add to
  every authenticated request in state B if the cache misses.

**User:** the diff compares the user *row* (`id, username, email, bio, image` — the hash is never
returned by the service and never part of the comparison) and the boolean/ids of the follow
queries. `token` is composed in the monolith on both paths and is not compared. Watch especially
the `followedIds` order (feed ordering depends on `follows` rowid order on each side, which is the
same after a clean backfill but not guaranteed after repairs) and `following:false` for anonymous
profile reads.

Exit criterion: ≥ 99.99 % agreement over ≥ 24 h with every mismatch explained (typically an
in-flight dual-write). Keep hourly reconciliation running.

## 5. Enter state B — `read=extracted`

```sh
EXTRACTION_USER_READ=extracted <restart monolith>   # fallback stays 'monolith'
```

From now on `JwtTokenFilter` resolves `sub -> User` through the routed `UserQueryPort.findById`:
a hit in the 30 s in-process cache costs nothing, a miss is one `GET /internal/users/{id}`, and a
remote failure (timeout, 5xx, connection refused) falls back to the local `users` table
(`extraction.user.fallback=monolith`) — the request is **never** turned into a 401 by an outage.
An unknown or invalid token stays anonymous exactly as today.

Verify:

```sh
curl -s -H "Authorization: Token $JWT" localhost:8080/user | jq '.user | {username, email, bio, image}'   # same as before the flip
curl -s localhost:8080/profiles/<username> | jq '.profile'                                             # identical, following:false
curl -s -H "Authorization: Token $JWT" localhost:8080/profiles/<followed username> | jq '.profile.following'   # true
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/profiles/nobody-here                            # 404
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"user":{"email":"<email>","password":"<correct>"}}' localhost:8080/users/login | jq '.user.username'   # 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
  -d '{"user":{"email":"<email>","password":"wrong"}}' localhost:8080/users/login                        # 422 invalid email or password
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
  -d '{"user":{"email":"nobody@example.com","password":"x"}}' localhost:8080/users/login                # 422, same envelope
curl -s 'localhost:8080/articles?limit=3' | jq '[.articles[].author.username]'                          # same as before
curl -s -H "Authorization: Token $JWT" localhost:8080/articles/feed | jq '.articlesCount'               # same as before
favorite-sync reconcile --domain user --source dev.db --target user-service/user.db; echo user=$?        # 0
```

**User — login path.** In state B `POST /users/login` is `findByEmail` (remote) followed by
`POST /internal/users/{id}/credentials/verify {"password":"<plain>"}` → `{"valid":true|false}`;
the monolith then issues the token itself. Unknown email and wrong password produce the same
`422 {"errors":{"password":["invalid email or password"]}}` envelope as today (the service answers
`{"valid":false}` for an unknown id — never 404, never a log line with the password). Confirm the
service log shows **no** plaintext password and **no** hash for the login attempts above. If
`credentials/verify` fails (timeout/5xx), login falls back to the local `passwordEncoder.matches`
against the local row — the two hashes are identical in states A/B, so the answer is the same.

**User — filter cache and fallback drill.** Stop `user-service` briefly:

```sh
kill -STOP <user-service pid>
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Token $JWT" localhost:8080/user            # 200 within 30 s (cache), then 200 via fallback (slower by the connect timeout, 500 ms)
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Token $JWT" localhost:8080/articles/feed   # 200
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Token invalid" localhost:8080/user         # 401 as today — no remote call is made for an invalid token
kill -CONT <user-service pid>
```

A `401`/`500` on a valid token while the service is down means the fallback is not wired for that
path — roll back to A' (`EXTRACTION_USER_READ=shadow`) and fix before continuing. Also run the
`seleniumTest` task (UI login) against state B once.

**Rollback from A/A'/B is a single flag** (05 §6.1, phase-5 §6.1): `EXTRACTION_USER_ENABLED=false`
(or `EXTRACTION_USER_READ=monolith` to keep dual-write warm). No data work is needed — every write
landed in `dev.db` first, and **every token stays valid** because the issuer never moved.

## 6. Cutover to state C — service authoritative (05 §5.2, §5.3)

Entry criteria: N days of `CLEAN` on both tables (step 3), zero dead-lettered outbox rows, the
rollback drill (§8) executed once in a non-production environment, `seleniumTest` green in state B,
an owner on call.

```sh
favorite-sync reconcile --domain user --source dev.db --target user-service/user.db; echo user=$?   # must be 0 right before the flip
EXTRACTION_USER_WRITE=extracted <restart monolith>
date -u +%Y-%m-%dT%H:%M:%SZ   # record T_flip
```

From now on registrations, profile updates and follow/unfollow go only to the service; `dev.db`'s
`users`/`follows` become a read-replica kept warm by the reverse repair below — and they still
serve as the `JwtTokenFilter` **fallback** when the service is unreachable, so keep the replica
fresh. **The monolith still hashes the password and still issues the JWT**; the service receives the
hash and its uniqueness `422` is now the one the user sees (same envelope as the local validators
produce — confirm once with a duplicate email and a duplicate username). Articles, comments and
favorites keep storing the `user_id` the monolith generated before the remote call, so it is the
same value on every side. Continue hourly reconciliation, now labelled:

```sh
favorite-sync reconcile --domain user --source dev.db --target user-service/user.db --authoritative service \
  --report build/reconcile/user/$(date -u +%Y%m%dT%H%M%SZ).json
```

With `--authoritative service` the interesting buckets are `extraInService` (= users/follows the
monolith replica is missing), `missingInService` (= unfollows done in the service, still in the
replica) and `diverged` (= profile/password updates not yet in the replica). To keep the replica
warm — and the filter fallback able to authenticate users registered after T_flip — run
periodically:

```sh
favorite-sync reconcile --domain user --source dev.db --target user-service/user.db --authoritative service --repair to-source
```

**User:** without `--delete-extras` the replica keeps follow pairs that were removed in state C;
they only matter for a rollback (the user would appear to follow again) and are removed by
`--repair to-source --delete-extras`, which deletes replica-only `follows` pairs (and replica-only
`users` rows, which cannot arise since there is no delete endpoint). A `uniqueConflictsInMonolith`
entry here means a user changed username/email in state C to a value a *replica-only* row still
holds — resolve by id (delete the stale replica row) before a rollback.

The monolith tables are dropped only after ≥ 30 days without rollback, via a new Flyway migration.
**User:** dropping `users` also removes the `JwtTokenFilter` fallback and the local login fallback;
that migration must ship together with `extraction.user.fallback=none` (or an equivalent) and is a
separate approval, as is moving the JWT issuer (phase-5 open question).

## 7. Rollback from state C (05 §6.2, phase-5 §6.2)

1. Pause registrations for the flip duration if possible (a user registered between the flip and
   the reverse-backfill is still recovered by step 3, but cannot authenticate against the replica
   until then — see the note under step 2).
2. Flip back to monolith-first:
   ```sh
   EXTRACTION_USER_WRITE=dual-write EXTRACTION_USER_READ=monolith <restart monolith>
   ```
   (or `EXTRACTION_USER_ENABLED=false` to go fully local; `dual-write` keeps a retry cheap).
   **Tokens issued in state C remain valid** — same issuer, same secret; the filter now resolves
   `sub` against the local replica, so a user whose row has not yet been copied back (the window
   between T_flip and step 3, minus the last reverse repair) is treated as anonymous until step 3
   completes. That is why step 1 pauses registrations and why step 3 follows immediately.
3. Reverse-backfill the authority-flip window — copies every user and follow pair that exists only
   in the service into the monolith (`users` by `id`, then `follows` by pair), leaving monolith
   rows untouched:
   ```sh
   favorite-sync reverse-backfill --domain user --source dev.db --target user-service/user.db \
     | tee build/reconcile/user/reverse-backfill.log; echo exit=$?
   ```
   Expected:
   ```
   reverse-backfill: copying user-service/user.db -> dev.db
   backfill domain=user table=users source=user-service/user.db target=dev.db chunk=5000
   backfill done rowsRead=5144 rowsInserted=24 rowsSkipped=5120 chunks=2 targetRowsAfter=5144 T0=... table=users
   backfill domain=user table=follows source=user-service/user.db target=dev.db chunk=5000
   backfill done rowsRead=9351 rowsInserted=43 rowsSkipped=9308 chunks=2 targetRowsAfter=9353 T0=... table=follows
   backfill done domain=user tables=2 rowsRead=14495 rowsInserted=67 rowsSkipped=14428 chunks=4 T0=...
   exit=0
   ```
   `rowsInserted` is the size of the window that the reverse repair had not delivered. The hashes
   of the new users travel with the rows, so they can log in immediately after this step.
   **User:** `exit=1` with `backfill conflict table=users <service id> email=<e> held by <monolith
   id>` (or `username=`) means a state-C user took a username/email that a stale replica row still
   holds (a rename in state C whose reverse repair was skipped, see §6). Resolve **by id first**
   (phase-5 §6.2): the replica copy is stale, so step 4's `--repair to-source --delete-extras`
   removes it (if it is replica-only) or `--repair to-source` overwrites it (if it is a rename of
   an id that exists on both sides — the swap case needs a manual `update` of the stale row to a
   free value first), then re-run the reverse-backfill (idempotent) so the new user lands.
4. Verify: zero extra-in-service and zero diverged.
   ```sh
   favorite-sync reconcile --domain user --source dev.db --target user-service/user.db --authoritative service \
     --report build/reconcile/user/rollback-$(date -u +%Y%m%dT%H%M%SZ).json; echo exit=$?
   ```
   Expected: `extra=0 diverged=0 conflicts=0/0` on both tables. `missing>0` on `follows` means the
   replica holds pairs unfollowed in state C; the service's view is the truth for the window, so
   apply it: `--repair to-source --delete-extras`. `diverged>0` on `users` is a profile/password
   update of the window that the reverse repair had not delivered; `--repair to-source` overwrites
   the monolith copy from the service (the hash included — the user keeps the password they set in
   state C). `["password"]` alone, with no password change in the window, is a defect to
   investigate — but repair toward the service anyway: it is the store the user authenticated
   against most recently.
5. Because there are no FK constraints (05 §1.2, phase-5 §6.3) nothing else in `dev.db`,
   `article.db`, `comment.db` or `favorite.db` is affected: articles, comments and favorites
   written during state C still reference the user ids that were just copied back.
   Re-populating `users` never changes an id.
6. Sanity-check the user-visible paths with the flag off:
   ```sh
   EXTRACTION_USER_ENABLED=false <restart monolith>
   curl -s -H "Authorization: Token $JWT_ISSUED_IN_STATE_C" localhost:8080/user | jq '.user.username'   # 200 — token still valid, row present
   curl -s -X POST -H 'Content-Type: application/json' \
     -d '{"user":{"email":"<email registered in state C>","password":"<its password>"}}' localhost:8080/users/login | jq '.user.username'   # 200
   curl -s localhost:8080/profiles/<username registered in state C> | jq '.profile.username'          # 200
   curl -s -H "Authorization: Token $JWT_ISSUED_IN_STATE_C" localhost:8080/articles/feed | jq '.articlesCount'   # same as the service showed
   ```
   A user registered in state C must be able to log in with the password they chose, and the
   `following` flags / feed must be exactly what the service showed before the rollback.

## 8. Rollback drill (05 §6.4) — run once per environment before step 6

Use copies of the databases, never production files.

```sh
cp dev.db drill-dev.db && cp user-service/user.db drill-user.db
```

| # | Action | Command / flag | Assert |
|---|---|---|---|
| 1 | Reach state B | steps 1, 2, 3, 5 above on the drill DBs | `reconcile --domain user ...` → `exit=0` |
| 2 | Kill the service mid-traffic; register, update, follow, unfollow, and make authenticated article/feed requests | `kill <user-service pid>`; `curl -X POST /users`, `PUT /user`, `POST/DELETE /profiles/{u}/follow`, `GET /user`, `GET /articles/feed` | every request 2xx (401 only for genuinely invalid tokens); rows present/changed/gone in `drill-dev.db`; login with a new user works |
| 3 | Restart the service, let the outbox drain | — | `favorite-sync reconcile --domain user --source drill-dev.db --target drill-user.db` → `status=CLEAN` on both tables, `exit=0` (use `--repair to-target --delete-extras` once if the outbox dead-lettered an unfollow) |
| 4 | Flip to state C and generate writes | `EXTRACTION_USER_WRITE=extracted`; register two users, change one's bio and password, follow with one, unfollow a pre-existing pair | `reconcile --domain user ... --authoritative service` shows `users: extra=2 diverged=1`, `follows: extra=1 missing=1` |
| 5 | **User:** provoke a uniqueness conflict | in state C rename a pre-existing user (`PUT /user` with a new username) before any reverse repair, then register a new user with the *old* username | `reverse-backfill --domain user` in step 6 prints `backfill conflict table=users ... username=<old> held by <renamed id>` and exits `1` |
| 6 | Execute §7 | `EXTRACTION_USER_WRITE=dual-write EXTRACTION_USER_READ=monolith`; `reverse-backfill --domain user` on the drill DBs | `rowsInserted` on `users` = 2 (the conflicting one skipped), on `follows` = 1 |
| 7 | Resolve by id | `favorite-sync reconcile --domain user --source drill-dev.db --target drill-user.db --authoritative service --repair to-source --delete-extras`; re-run `reverse-backfill --domain user` | the renamed user's replica row now carries the new username, the conflicting new user is inserted (`rowsInserted=1`), the unfollowed pair is gone from the replica |
| 8 | Verify | `favorite-sync reconcile --domain user --source drill-dev.db --target drill-user.db --authoritative service; echo exit=$?` | `extra=0 diverged=0 conflicts=0/0` on both tables, `exit=0` |
| 9 | **User:** auth round-trip with the flag off | `EXTRACTION_USER_ENABLED=false`; `GET /user` with a token issued in step 4; `POST /users/login` for the user whose password changed in step 4, with the **new** password | `200` both; the old password is rejected with `422` |
| 10 | Idempotency | run steps 6 and 8 a second time | `rowsInserted=0`, `exit=0` |
| 11 | **User:** hash hygiene | `grep -c '\$2a\$' build/reconcile/user/*.json build/reconcile/user/*.log` and the service/monolith logs of the drill | `0` everywhere |

Record the eleven results (with the JSON reports) in the change ticket; this is entry criterion 3
of 05 §5.2.

## 9. Quick reference

| Situation | Command |
|---|---|
| Hourly drift check (alert on exit 1) | `favorite-sync reconcile --domain user --source dev.db --target user-service/user.db --report build/reconcile/user/$(date -u +%Y%m%dT%H%M%SZ).json` |
| Service missing users/follows or a diverged profile (states A/B) | `favorite-sync reconcile --domain user --source dev.db --target user-service/user.db --repair to-target` |
| Service has rows the monolith does not, or a username/email held by a service-only id (states A/B) | `favorite-sync reconcile --domain user --source dev.db --target user-service/user.db --repair to-target --delete-extras` |
| Username/email held by an id that exists on both sides | resolve by id by hand (`PUT /user` through the monolith in states A/B, `update users set ...` on the stale side in C), then re-run reconcile — never auto-repaired |
| `diverged` with `["password"]` and no password change | defect in the mirror; repair toward the authoritative side, never inspect the values |
| Rebuild service DB from scratch (states A/B) | stop service, delete `user-service/user.db`, start service (Flyway), `favorite-sync backfill --domain user ...`, then `reconcile` |
| Service down in state B/C | nothing — `JwtTokenFilter` serves from the 30 s cache then the local `users` table, login falls back to the local hash check; keep the replica warm in C |
| Rollback from A/A'/B | `EXTRACTION_USER_ENABLED=false` — no data work, no token invalidated |
| Rollback from C | `EXTRACTION_USER_WRITE=dual-write EXTRACTION_USER_READ=monolith`, `reverse-backfill --domain user`, `reconcile --domain user ... --authoritative service [--repair to-source --delete-extras]` — §7; tokens stay valid |
| Keep the monolith replica (and the auth fallback) warm in state C | `favorite-sync reconcile --domain user --source dev.db --target user-service/user.db --authoritative service --repair to-source` |
| Articles/comments/favorites of a moved user | nothing — they reference `user_id` by value, no FK, ids never change |
| Moving the JWT issuer to `user-service` | out of scope — separate approval (phase-5 open question); until then no flag in this runbook can invalidate a token |
