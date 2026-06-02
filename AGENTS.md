# AGENTS.md — uc-spring-boot-upgrade-microservice-extraction

## Repository Purpose

Spring Boot 2.6.3 / Java 11 monolith implementing the RealWorld blogging platform (Conduit). 4 domain contexts: articles/tags, comments, favorites, users/profiles. REST and GraphQL (DGS) APIs, MyBatis persistence with SQLite, Flyway migrations, 27 test files with an 80% JaCoCo coverage gate.

## Microservice Extraction Standards

### Build Configuration

- The extracted service must have its own independent `build.gradle` with all required dependencies
- Do not attempt to fix pre-existing CI thresholds (e.g., JaCoCo coverage gates) — use `-x jacocoTestCoverageVerification` to skip coverage verification when running `./gradlew build`
- Include Flyway migrations for the extracted service's schema in `src/main/resources/db/migration/`
- Both the original monolith and the extracted service must build independently: `./gradlew build` must pass for each

### Cross-Service Communication

- Replace direct domain object references with a REST client and DTOs
- Create a dedicated client class (e.g., `UserServiceClient`) in the extracted service for calling the monolith
- DTOs for cross-service communication belong in the extracted service, not in a shared module
- Handle REST client failures gracefully — catch exceptions and return sensible defaults or throw domain-specific exceptions with clear error messages

### Database

- The extracted service gets its own database file — no shared SQLite databases
- Flyway migration scripts use `V1__` naming convention
- Copy only the relevant entity tables and seed data to the extracted service

### Code Quality

- Follow existing code conventions: MyBatis XML mappers, Spring `@Service`/`@Repository` annotations, constructor injection
- Maintain the existing package structure pattern: `api/`, `core/`, `application/`, `infrastructure/`
- Use the same Spring Boot version (2.6.3) and dependency versions as the monolith
- All new REST endpoints must follow the existing URL patterns and response envelope format
