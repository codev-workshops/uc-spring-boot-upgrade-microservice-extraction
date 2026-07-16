# Comments Service Java 17 Upgrade

The upgrade is split into independently testable stages so dependency, framework, and Java
runtime failures remain attributable to one change set.

## Baseline

- Spring Boot 2.6.3
- Java source/target 11
- Gradle 7.4

Before each stage:

```bash
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./gradlew clean test \
  -x jacocoTestCoverageVerification --no-daemon
```

## Stage 1: dependencies on Java 11

Upgrade libraries and build tooling while retaining Spring Boot 2.6.3 and Java 11:

| Component | From | To |
| --- | --- | --- |
| Gradle | 7.4 | 7.6.4 |
| Spotless | 6.2.1 | 6.25.0 |
| JaCoCo | 0.8.7 | 0.8.12 |
| MyBatis Spring Boot starter/test | 2.2.2 | 2.3.2 |
| JJWT | 0.11.2 | 0.11.5 |
| Joda-Time | 2.10.13 | 2.12.7 |
| SQLite JDBC | 3.36.0.3 | 3.45.3.0 |
| Lombok | Boot-managed 1.18.22 | 1.18.32 |
| REST Assured | 4.5.1 | 5.4.0 |
| Mockito inline | 4.0.0 | 5.2.0 |

Gate:

```bash
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./gradlew clean test spotlessCheck \
  -x jacocoTestCoverageVerification --no-daemon
```

## Stage 2: framework on Java 11

Upgrade Spring Boot to 2.7.18, the final Spring Boot release that supports Java 11, and migrate
the deprecated `WebSecurityConfigurerAdapter` configuration to a `SecurityFilterChain`.

Gate:

```bash
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./gradlew clean test spotlessCheck \
  -x jacocoTestCoverageVerification --no-daemon
```

## Stage 3: Java 17 language level

Change the Gradle source and target compatibility and `.java-version` to 17. No Java 17-only
syntax is introduced in this compatibility upgrade.

Gate:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew clean test spotlessCheck \
  -x jacocoTestCoverageVerification --no-daemon
```

## Compatibility decisions

No current runtime library lacks a Java 17-compatible release. Some newer major versions cannot
be used in the earlier Java 11 stages:

- Spring Boot 3.x requires Java 17 and Jakarta APIs, so it cannot be the framework stage while
  Java 11 remains the active runtime. Spring Boot 2.7.18 is the compatible bridge. A later,
  separate Boot 3 migration should change `javax.servlet`/`javax.validation` imports to
  `jakarta.*`.
- MyBatis Spring Boot starter 3.x targets Spring Boot 3; use 2.3.2 with Boot 2.7.18, then move to
  3.x during a Boot 3 migration.
- Spotless 8.x requires a newer Gradle/JVM baseline than the Java 11 stage; 6.25.0 supports both
  Java 11 and Java 17.
- REST Assured 6.x requires Java 17, so 5.4.0 is used until the language stage is complete.
- `mockito-inline` ends at 5.2.0 because inline mocking became Mockito's default mock maker.
  Keep 5.2.0 for this low-risk upgrade; a follow-up can remove it and use `mockito-core`.

Joda-Time remains Java 17 compatible. Replacing it with `java.time` is recommended separately
because it changes cursor, JSON, and MyBatis timestamp behavior rather than merely the runtime
baseline.

## Final proof

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew clean build \
  -x jacocoTestCoverageVerification --no-daemon
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew test \
  -x jacocoTestCoverageVerification --rerun-tasks --no-daemon
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew spotlessCheck --no-daemon
JWT_SECRET=<shared-secret> JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew bootRun
```
