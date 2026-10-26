# Testing Gaps: Java 17 / Spring Boot 3.2.12 upgrade

This file lists what was **not** verified in this environment (Devin Ubuntu VM, JDK 17, no CI in the repo) and why. Verified results are in MIGRATION_PROOF.md.

## 1. Skipped or `@Disabled` tests

- No test is `@Disabled`, `@Ignore`d, or excluded by this change. `grep -rn "@Disabled\|@Ignore" src/test` gives no matches. All 68 JUnit tests ran and passed before and after.
- Two exclusions already existed and were left unchanged:
  - `jacocoTestCoverageVerification` (80% instruction gate) is skipped with `-x`. Coverage was 33.2% before and is 32.0% after, so the gate fails on the base branch too. `AGENTS.md` says to skip it rather than fix it.
  - The `test` task excludes `io/spring/selenium/**`. Those classes run only through the separate `seleniumTest` TestNG task (see section 3).

## 2. Integration testing that needs a real database

- Tests and the smoke run used **SQLite only**: in-memory for `@MybatisTest`/`@SpringBootTest` with `spring.flyway.target=1`, and a file-based `dev.db` for the `java -jar` run. No production-grade database (MySQL, Postgres, etc.) is configured in the repo, so no MyBatis SQL, Flyway 9 migration or `sqlite-jdbc` 3.43 behaviour was checked against one.
- The Flyway 8 → 9 upgrade was checked on a **fresh** database only (V1 + V2 applied cleanly). A migration over an existing database created by the old build (`flyway_schema_history` written by Flyway 8.0.5) was not tested.
- Stored data was not compared between the two versions, for example the joda-time `DateTime` type handlers across the `sqlite-jdbc` 3.36 → 3.43 upgrade.

## 3. E2E testing that needs a running server

- The backend was started from the built jar, and the REST, GraphQL, auth and CORS paths in MIGRATION_PROOF.md section 6 were smoke-tested with curl. That is a spot check, not a full suite.
- The Next.js frontend (`frontend/`, Node 16) was started, and `DISPLAY=:0 ./gradlew seleniumTest` passed 2/2. That suite (`testng-smoke.xml` → `SeleniumSetupTest`) only checks that Chrome and WebDriverManager start (it opens google.com). **No UI flow against the Conduit app** (sign up, log in, write an article, comment, favorite, follow) was run, because the repo has no such Selenium tests.
- The frontend was not changed or rebuilt for this upgrade. Its `npm run build` and `tsc` checks were not run.

## 4. Performance and load testing

- None was done. Startup time on JDK 17 (about 2.4 s) is the only timing taken. There was no load generator, baseline throughput or latency budget, and no comparison of Tomcat 9 vs 10.1, Hikari 4 vs 5, or JVM 11 vs 17 behaviour under load.

## 5. Security scanning that needs network access or setup

- **OWASP Dependency-Check**: the plugin is **not configured** in `build.gradle` (`./gradlew tasks --all` lists no `dependencyCheck*` task), so `./gradlew dependencyCheckAnalyze` could not run and there is no report. Running it would mean adding `org.owasp.dependencycheck` (latest 12.x) and downloading the NVD database, which needs network access and ideally an NVD API key.
- No SAST, container or secret scanning was run as part of this upgrade.
- Known and not changed: `jwt.secret` is hard-coded in `src/main/resources/application.properties`, and CORS allows `*`.

## 6. Other environment limits

- Only OpenJDK 17.0.20 on Linux was tested. Other JDK vendors and Java 21 were not.
- There is no CI pipeline in the repo, so these results come only from local runs on the Devin VM.
- Build warnings that come from third-party code are listed in MIGRATION_PROOF.md section 5: DGS codegen 6.3.0 uses the deprecated Gradle `Convention` type, which matters only for Gradle 9.
