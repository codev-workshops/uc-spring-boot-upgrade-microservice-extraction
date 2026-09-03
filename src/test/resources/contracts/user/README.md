# user-service User contracts (monolith as CONSUMER)

Like `../article`, `../tag` and `../comment`, these describe what the monolith's
`UserServiceClient` expects from **user-service** (the producer): the canonical internal API of
`docs/microservice-extraction/phases/phase-5-user.md` section 2.1 — one contract per endpoint of
the table (plus the 404 and duplicate-username cases).

They are *excluded* from the monolith's producer verification (`contracts { excludedFiles }` in
`build.gradle`) and are meant to be copied verbatim into
`user-service/src/test/resources/contracts/` where the generated tests run against the service.
On the monolith side the same envelopes are replayed by `UserServiceClientTest` from
`src/test/resources/user-service-stubs`, so both sides pin the identical shape.

Promises the monolith relies on: rows never carry `password`/`passwordHash`; the monolith hashes
(BCrypt) and ships `passwordHash` on register/update, and asks `credentials/verify` with the plain
password at login (never 404, never logged); reads take no credentials, writes require
`Authorization: Token <jwt>` whose subject equals `{id}` (registration is anonymous); blank fields
on `PUT` are skipped exactly like `UserMapper.xml#update`; `following?ids=` returns the subset of
`ids` followed; `follows` writes are idempotent `204`.
