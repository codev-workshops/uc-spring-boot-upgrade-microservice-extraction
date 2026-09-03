# Microservice extraction roadmap (strangler pattern)

Domain-by-domain extraction of the Conduit monolith (Spring Boot 2.6.3 / Java 11,
MyBatis + SQLite, Flyway). Each phase requires explicit user approval of its design
document before implementation starts, and explicit confirmation before advancing to
the next phase.

| Phase | Domain | Tables that move | Design doc | Gate |
|-------|--------|------------------|------------|------|
| 0 | Harness + design | none | this directory | done when docs approved |
| 1 | Favorite | `article_favorites` | [phases/phase-1-favorite.md](phases/phase-1-favorite.md) · [runbook](runbooks/phase-1-favorite-cutover.md) | approved, implemented (PR #15) |
| 2 | Comment | `comments` | [phases/phase-2-comment.md](phases/phase-2-comment.md) · [runbook](runbooks/phase-2-comment-cutover.md) | approved, implemented (PR #23) |
| 3 | Tag (bundled with Article) | `tags`, `article_tags` | [phases/phase-3-tag.md](phases/phase-3-tag.md) · [runbook](runbooks/phase-3-tag-cutover.md) | approved, implemented (PR #27) |
| 4 | Article | `articles` | [phases/phase-4-article.md](phases/phase-4-article.md) · [runbook](runbooks/phase-4-article-cutover.md) | approved, implemented (PR #31) |
| 5 | User | `users`, `follows` | [phases/phase-5-user.md](phases/phase-5-user.md) · [runbook](runbooks/phase-5-user-cutover.md) | approved, implementation in progress |
| — | Wrap-up | none | [06-post-extraction-state.md](06-post-extraction-state.md) | end state + two open program questions for the user |

## Phase 0 documents

| Doc | Produced by | Purpose |
|-----|-------------|---------|
| `00-golden-test-baseline.md` | test-harness uplift | the 27 pre-existing test files as the golden regression baseline |
| `01-contract-testing.md` | test-harness uplift | consumer-driven contract scaffolding for future service boundaries |
| `02-parallel-run-harness.md` | test-harness uplift | flag-OFF / flag-ON envelope-equality harness |
| `templates/03-extracted-service-template.md` + `templates/skeleton/` | scaffolding template | per-service Gradle/Flyway/package checklist and file templates |
| `04-strangler-wiring-design.md` | strangler wiring design | feature flags, routing seams, REST clients + DTOs, auth propagation |
| `05-data-sync-and-rollback-design.md` | data sync design | dual-write, backfill, reconciliation, rollback |
| `phases/*.md` | orchestrator | per-domain execution plan assembled from the above |

## Per-phase deliverables

| Doc | Purpose |
|-----|---------|
| `runbooks/phase-N-*-cutover.md` | operator procedure per domain: dual-write, T0 + backfill, reconcile, shadow reads, extracted reads, cutover, rollback, rollback drill |
| [`tools/favorite-sync/README.md`](../../tools/favorite-sync/README.md) | the sync CLI (`--domain favorite\|comment\|tag\|article\|user`): backfill, reverse-backfill, reconcile/repair, report format, exit codes |
| `06-post-extraction-state.md` | end-state overview after Phase 5: four services (8081–8084), the monolith as strangler façade (JWT issuer, public REST/GraphQL, composition), every flag and default, sync domains, open questions |

## Invariants that hold for every phase

1. The monolith `./gradlew build -x jacocoTestCoverageVerification` stays green after every merge
   (the JaCoCo 80% gate is pre-existing and is not fixed as part of this work, per AGENTS.md).
2. The monolith's copy of a domain table stays **authoritative** until the phase's cutover step;
   rollback is a feature-flag flip and loses no data.
3. The schema has **no foreign-key constraints** (`V1__create_tables.sql`), so a domain's tables can
   be dual-written, backfilled, and later dropped without cascading effects.
4. Response envelopes (`{"article": ...}`, `{"comments": [...]}`, error format) are byte-identical
   between the monolith path and the extracted path; verified by the parallel-run harness.
5. Each extracted service has its own `build.gradle`, own SQLite file, own `V1__` Flyway migration,
   and the `api/core/application/infrastructure` package layout (AGENTS.md).

## Build note

Spotless (google-java-format 1.x via Spotless 6.2.1) fails on JDK 17+; use a JDK 11
(`JAVA_HOME=/path/to/jdk-11 ./gradlew build -x jacocoTestCoverageVerification`).
