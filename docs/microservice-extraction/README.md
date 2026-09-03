# Microservice extraction roadmap (strangler pattern)

Domain-by-domain extraction of the Conduit monolith (Spring Boot 2.6.3 / Java 11,
MyBatis + SQLite, Flyway). Each phase requires explicit user approval of its design
document before implementation starts, and explicit confirmation before advancing to
the next phase.

| Phase | Domain | Tables that move | Design doc | Gate |
|-------|--------|------------------|------------|------|
| 0 | Harness + design | none | this directory | done when docs approved |
| 1 | Favorite | `article_favorites` | [phases/phase-1-favorite.md](phases/phase-1-favorite.md) | approval pending |
| 2 | Comment | `comments` | [phases/phase-2-comment.md](phases/phase-2-comment.md) | approval pending |
| 3 | Tag (bundled with Article) | `tags`, `article_tags` | [phases/phase-3-tag.md](phases/phase-3-tag.md) | approval pending |
| 4 | Article | `articles` | [phases/phase-4-article.md](phases/phase-4-article.md) | approval pending |
| 5 | User | `users`, `follows` | [phases/phase-5-user.md](phases/phase-5-user.md) | approval pending |

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
