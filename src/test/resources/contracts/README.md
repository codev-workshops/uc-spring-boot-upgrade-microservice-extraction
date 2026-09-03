# Contracts

Spring Cloud Contract DSL files (Groovy) for the monolith as a **producer**.

Layout: one directory per future service boundary, e.g. `favorite/` holds the contracts the future
Favorite service (consumer) relies on when it calls the monolith.

## Workflow

1. The consumer (the extracted service) proposes a contract here, describing only the request and
   the response fields it actually needs.
2. `./gradlew contractTest` generates JUnit 5 tests from these files into
   `build/generated-test-sources/contractTest` and runs them against the monolith with standalone
   MockMvc — no running service, no database. The generated test extends the base class configured
   in `build.gradle` (`contracts { baseClassForTests = ... }`), currently
   `io.spring.contract.FavoriteContractBase`, which stubs the collaborators and returns the fixture
   the contract expects.
3. `./gradlew generateClientStubs` (or the `verifierStubsJar` artifact produced by `build`) packages
   the same contracts as WireMock stubs. The consumer runs them with
   `@AutoConfigureStubRunner(ids = "io.spring:realworld:+:stubs")` instead of mocking by hand, so
   both sides break as soon as the contract drifts.

## Rules

- A contract is a promise. Changing an existing contract means the consumer must agree first;
  additive changes (new endpoints, new optional fields) are safe.
- Assert only the fields the consumer needs. Over-specifying (timestamps, ids, unrelated fields)
  makes the producer unable to evolve.
- Every contract needs the corresponding fixture in the base class, otherwise generation succeeds
  and verification fails.

## Current contracts

- `favorite/shouldReturnArticleBySlug.groovy` — **illustrative**. `GET /articles/{slug}` returning
  the article envelope (`id`, `slug`, `title`, `favorited`, `favoritesCount`). Phase 1 extracts the
  Favorite domain but still has to resolve a slug to an article id via the monolith, so this pins
  the shape it depends on. It is a placeholder to demonstrate the mechanics; replace it with the
  real contract when the Favorite service exists.
- `comment/*.groovy` — **consumer-side**. The canonical internal API of comment-service
  (phase-2-comment.md section 2.1) as the monolith's `CommentServiceClient` expects it. Excluded
  from producer verification here (`contracts { excludedFiles = ['comment/**'] }`); comment-service
  verifies them as its provider contracts. See `comment/README.md`.
- `tag/*.groovy` — **consumer-side**. The Tag part of article-service's canonical internal API
  (phase-3-tag.md section 2.1) as the monolith's `ArticleServiceClient` expects it. Excluded from
  producer verification here (`excludedFiles = [..., 'tag/**']`); article-service verifies them as
  its provider contracts. See `tag/README.md`.
