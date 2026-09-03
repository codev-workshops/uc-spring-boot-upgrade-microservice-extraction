# Consumer-driven contract testing

Scaffolding only — Phase 0 adds the mechanics so that Phase 1 (Favorite extraction) can express the
Favorite -> Article boundary as an executable contract instead of prose.

## Choice: Spring Cloud Contract

Spring Cloud Contract 3.1.1 (release train 2021.0.1) is the release line that matches Spring Boot
2.6.3, so no dependency downgrade or upgrade of the monolith is needed. It was preferred over Pact
JVM because:

- the producer side is generated, so the monolith cannot silently drift from the contract;
- verification runs with MockMvc inside the existing Gradle build — no broker, no running service,
  nothing to host;
- the same contract files also produce WireMock stubs the consumer can run, which is exactly the
  handover the strangler pattern needs.

Pact would additionally require a Pact Broker (or committed pact files plus a publishing step) to be
useful across two repositories; that can be revisited if more consumers appear.

## Wiring

`build.gradle`:

```groovy
plugins { id 'org.springframework.cloud.contract' version '3.1.1' }

dependencyManagement {
    imports { mavenBom 'org.springframework.cloud:spring-cloud-dependencies:2021.0.1' }
}

contracts {
    testFramework = 'JUNIT5'
    testMode = 'MOCKMVC'
    baseClassForTests = 'io.spring.contract.FavoriteContractBase'
    contractsDslDir = file("${project.projectDir}/src/test/resources/contracts")
}

dependencies {
    testImplementation 'org.springframework.cloud:spring-cloud-starter-contract-verifier'
}

tasks.named('contractTest') { useJUnitPlatform() }
```

`useJUnitPlatform()` on `contractTest` is required: without it the task silently executes zero tests
(the plugin's task defaults to JUnit 4's runner).

Files:

- `src/test/resources/contracts/README.md` — the workflow, for contract authors.
- `src/test/resources/contracts/favorite/shouldReturnArticleBySlug.groovy` — illustrative contract.
- `src/test/java/io/spring/contract/FavoriteContractBase.java` — base class for generated tests.

## Running

```bash
export JAVA_HOME=/path/to/jdk-11
./gradlew contractTest        # generate + verify; also runs as part of `check`/`build`
./gradlew generateContractTests   # generation only, output in build/generated-test-sources/contractTest
```

## How the base class keeps it hermetic

`FavoriteContractBase` builds a standalone MockMvc around `ArticleApi` with Mockito stubs for
`ArticleQueryService`, `ArticleRepository` and `ArticleCommandService`, and registers
`JacksonCustomizations.RealWorldModules` on the message converter so the response envelope is
serialized exactly as production does. Two consequences worth knowing:

- The built `MockMvc` instance is handed to `RestAssuredMockMvc.mockMvc(...)` rather than a builder.
  Given a builder, rest-assured auto-applies the `spring-security-test` configurer (it is on the
  test classpath), which needs a `springSecurityFilterChain` bean and therefore a full context.
- Because security is not in play, the contract exercises the anonymous read path. Contracts that
  need an authenticated caller must add a JWT header in the DSL and a base class that boots the
  security configuration.

## Adding a contract

1. Add a `.groovy` file under `src/test/resources/contracts/<boundary>/`.
2. Give the base class a fixture that satisfies it (or add a new base class and point
   `baseClassForTests` / a `packageWithBaseClasses` convention at it).
3. `./gradlew contractTest`.
4. Publish stubs for the consumer with `./gradlew publishToMavenLocal` and consume them with
   `@AutoConfigureStubRunner`.

## Open questions

- **Stub publication target.** The extracted services live in separate repositories, so the stub jar
  has to reach them somehow (internal Maven repo vs. git-based stub locations vs. committing stubs).
  Not decided in Phase 0; `publishToMavenLocal` is enough for a single-machine spike.
- **Base class strategy.** One base class per boundary was chosen for clarity. If contracts multiply,
  switch to `packageWithBaseClasses` so the class is derived from the contract directory name.
- **Authenticated contracts.** All Favorite write-path contracts will need a JWT; the cleanest option
  is likely a base class extending the existing `TestWithCurrentUser` pattern, which was not built
  in Phase 0 because no real consumer exists yet.
