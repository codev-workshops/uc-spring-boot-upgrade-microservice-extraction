# Rollback / parallel-run harness

A strangler migration only stays safe if the extracted service can be proven to return the *same*
response envelope as the monolith, and if the switch back is a flag flip. Phase 0 adds the test-side
half of that: a way to drive the same scenario through either side of the migration and compare the
normalized JSON envelope against a recorded golden.

## Pieces

| File | Role |
| --- | --- |
| `src/test/java/io/spring/harness/RoutePath.java` | `MONOLITH` / `EXTRACTED` — which side of the migration serves the request |
| `src/test/java/io/spring/harness/ParallelRunHarness.java` | performs the request, normalizes the envelope, compares routes and goldens |
| `src/test/java/io/spring/harness/FavoriteParallelRunTest.java` | `@ParameterizedTest` over both routes for `GET /articles/{slug}` and `POST /articles/{slug}/favorite` |
| `src/test/resources/golden/favorite/*.json` | recorded envelopes |

## Feature flag

`extraction.favorite.enabled` is set to `false` via `@TestPropertySource` in the test.
**No production code reads it** — Phase 0 must not change `src/main` behaviour. It exists so that
Phase 1 can introduce the real flag with the same name and the harness immediately starts exercising
the extracted route: `ParallelRunHarness.supports(EXTRACTED)` returns `true` only when the flag is on.

Until then the `EXTRACTED` parameter is skipped with
`Assumptions.assumeTrue(harness.supports(route), ...)`, so it shows up as *skipped*, never as a
failure. The `MONOLITH` parameter runs today.

## Normalization

Envelopes are compared after normalization, otherwise every run differs:

- object fields are sorted, so field ordering is irrelevant;
- any UUID inside a string value becomes `<uuid>` (ids and slugs are generated per run);
- the fields `createdAt`, `updatedAt` and `cursor` become `<volatile>`.

Everything else — including `favorited` and `favoritesCount` — is compared verbatim, which is the
point: those are the fields Phase 1 is most likely to break.

## Running / recording

```bash
export JAVA_HOME=/path/to/jdk-11
./gradlew test --tests 'io.spring.harness.FavoriteParallelRunTest'

# intentionally refresh a golden: prints the current envelope instead of asserting
./gradlew test --tests 'io.spring.harness.FavoriteParallelRunTest' -Dharness.record=true --info
```

`build.gradle` forwards `-Dharness.record` to the test JVM. Recording never rewrites files on its
own — copy the printed envelope into the golden file deliberately, and treat the diff as the review
artefact.

## Using it in Phase 1

1. Extract the Favorite service and put the real `extraction.favorite.enabled` flag in front of the
   call site.
2. Point the `EXTRACTED` route at the new path (e.g. a second `MockMvc`, a `RestTemplate` against a
   running service, or a router bean) inside `ParallelRunHarness`.
3. Run with the flag on: both parameters now execute and
   `assertEnvelopesMatch(monolith, extracted)` becomes the actual parity assertion.
4. Roll out with the flag off, compare in production (shadow traffic) and flip. Rollback is the
   flag, not a redeploy.

## Open questions

- **Where the route switch lives.** The harness currently holds a single `MockMvc`; the extracted
  route needs either a second client or a routing abstraction in production code. That is a Phase 1
  decision, deliberately not pre-empted here.
- **Status codes.** `captureEnvelope` requires HTTP 200 today, so error-path parity (404/403/422
  envelopes) is not covered. Extending it to capture status plus body is straightforward and should
  happen once the error contracts matter.
- **Shadow comparison in production.** This harness proves parity in tests only. Whether the
  parallel run also happens at runtime (dual-write / dual-read with a diff metric) is a separate
  decision for the rollout plan.
