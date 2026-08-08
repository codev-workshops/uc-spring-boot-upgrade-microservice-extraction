---
name: build-and-test
description: Build, format-check and test this Spring Boot monolith (and any extracted microservice) with the JaCoCo gate and Java 17 spotless workarounds already applied. Use before committing, before opening a PR, or whenever verifying a change compiles and passes tests.
allowed-tools:
  - read
  - grep
  - glob
  - exec
---

# Build and test

Two things about this repo will waste your time if you don't know them up front: the JaCoCo
coverage gate is pre-existing and not yours to fix, and `spotless` cannot run on Java 16+ without
extra JVM exports. Both are handled below.

## Toolchain

`sourceCompatibility`/`targetCompatibility` are 11 and Gradle is 7.4, so build on **Java 17**.
A newer JDK on `PATH` (e.g. 25) breaks the Gradle 7.4 + DGS codegen combination.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

## The spotless / Java 17 workaround

`spotless` uses `googleJavaFormat`, which reaches into `jdk.compiler` internals. On Java 17 the
module system blocks it and any `spotlessJava*` task dies with:

```
java.lang.IllegalAccessError: class com.google.googlejavaformat.java.JavaInput
cannot access class com.sun.tools.javac.parser.Tokens$TokenKind (in module jdk.compiler)
```

This is not a problem with your change. Pass the exports to the Gradle daemon:

```bash
export SPOTLESS_EXPORTS='-Dorg.gradle.jvmargs=--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED'
```

(The same flags in `gradle.properties` under `org.gradle.jvmargs` would make this permanent —
propose that separately rather than folding it into an unrelated PR.)

## Commands

Full verification — this is the one that must pass before a PR:

```bash
./gradlew build -x jacocoTestCoverageVerification "$SPOTLESS_EXPORTS"
```

Tests only (fast loop):

```bash
./gradlew test -x jacocoTestCoverageVerification
```

Fix formatting rather than just detecting it:

```bash
./gradlew spotlessJavaApply "$SPOTLESS_EXPORTS"
```

Expected green baseline on `main`: `BUILD SUCCESSFUL`, 20 test classes, 68 tests, 0 failures.

## Why `-x jacocoTestCoverageVerification`

`jacocoTestCoverageVerification` enforces a **pre-existing** 80% line-coverage minimum that `main`
does not meet. Per `AGENTS.md`, do not try to satisfy it as a side effect of another change —
skip it. Chasing that gate is the single most common way to burn an hour in this repo.

`jacocoTestReport` still runs and still produces `build/reports/jacoco/`, so you can see coverage;
you just aren't blocked on the threshold.

## When a microservice has been extracted

Per `AGENTS.md`, the monolith and each extracted service must build **independently**. Run the
full command in both roots and report both results — a green monolith alone is not evidence.

## Interpreting failures

| Failure | Meaning |
|---|---|
| `IllegalAccessError … JavaInput` in `spotlessJava` | Missing the `--add-exports` flags above. Not your change. |
| `Task ':spotlessJava' uses this output of task ':compileJava' without declaring an explicit or implicit dependency` | Gradle task-graph warning from the spotless config; it turns fatal alongside the error above. Fixing the exports resolves it. |
| `spotlessJavaCheck` reports a diff | Real. Run `spotlessJavaApply` and commit the result — commonly import ordering after a `javax.*` → `jakarta.*` migration. |
| A `Datafetcher` / exception-handler compile error after a DGS bump | DGS changed interfaces between majors. Fix the implementation against the new interface; do not pin the version back. |
| Coverage verification failure | You forgot `-x jacocoTestCoverageVerification`. |

## Do not

- Do not lower or delete the JaCoCo threshold in `build.gradle` to make a build pass.
- Do not modify tests to make them pass. Fix the code, or explain why the test is wrong.
- Do not commit an unformatted diff — run `spotlessJavaApply` first.
