# Migration Proof: Java 11 / Spring Boot 2.6.3 → Java 17 / Spring Boot 3.2.12

Base branch: `asiri-progress` @ `7acb734`. Full build output: [`build-before.log`](build-before.log) and [`build-after.log`](build-after.log).

Both builds run `./gradlew clean build -x jacocoTestCoverageVerification`. The 80% JaCoCo gate already fails on the base branch (about 33% coverage), and `AGENTS.md` says to skip it rather than fix it. Everything else in `build` ran, including `spotlessCheck` and `test`.

## 1. Toolchain and platform versions

| Component | Before | After |
|---|---|---|
| Java (source/target) | 11 | **17** (`java { sourceCompatibility = JavaVersion.VERSION_17 }`) |
| Build JDK | OpenJDK 11.0.32.1 | OpenJDK 17.0.20.1 |
| Gradle wrapper | 7.4 | **8.9** |
| Spring Boot (plugin + BOM) | 2.6.3 | **3.2.12** |
| `io.spring.dependency-management` | 1.0.11.RELEASE | 1.1.7 |
| Spring Framework | 5.3.15 | 6.1.15 |
| Spring Security | 5.6.1 | 6.2.8 |
| Embedded Tomcat | 9.0.56 (javax) | 10.1.33 (jakarta) |
| Hibernate Validator | 6.2.0.Final (javax) | 8.0.1.Final (jakarta) |
| Netflix DGS | 4.9.21 | **8.7.1** (via `graphql-dgs-platform-dependencies` BOM, built against Boot 3.2) |
| graphql-java | 17.3 | 21.5 |
| DGS codegen plugin | 5.0.6 | 6.3.0 |
| Spotless plugin | 6.2.1 | 6.25.0 |
| JaCoCo | 0.8.7 | 0.8.12 |

## 2. Dependency versions (direct dependencies)

| Dependency | Before | After | Note |
|---|---|---|---|
| spring-boot-starter-web / validation / hateoas / security | 2.6.3 | 3.2.12 | Boot BOM |
| mybatis-spring-boot-starter (+ `-test`) | 2.2.2 | 3.0.3 | Boot 3.x line (MyBatis 3.5.9 → 3.5.14, mybatis-spring 2.0.7 → 3.0.3) |
| graphql-dgs-spring-boot-starter | 4.9.21 | 8.7.1 | DGS BOM |
| flyway-core | 8.0.5 | 9.22.3 | Boot BOM (SQLite support is still in core in 9.x) |
| jjwt-api / jjwt-impl / jjwt-jackson | 0.11.2 | 0.12.6 | moved to the non-deprecated 0.12 API |
| joda-time | 2.10.13 | 2.12.7 | |
| sqlite-jdbc | 3.36.0.3 | 3.43.2.2 | now managed by the Boot BOM |
| spring-hateoas | 1.4.1 | 2.2.5 | Boot BOM |
| jackson-databind | 2.13.1 | 2.15.4 | Boot BOM |
| HikariCP | 4.0.3 | 5.0.1 | Boot BOM |
| snakeyaml | 1.29 | 2.2 | Boot BOM |
| kotlin-stdlib (via DGS) | 1.6.10 | 1.9.25 | |
| rest-assured / json-path / xml-path / spring-mock-mvc | 4.5.1 | 5.3.2 | Boot BOM (jakarta-compatible 5.x, Groovy 3 → 4) |
| spring-boot-starter-test | 2.6.3 | 3.2.12 | JUnit Jupiter 5.8.2 → 5.10.5, AssertJ 3.21.0 → 3.24.2 |
| mockito | 4.0.0 (`mockito-inline`) | 5.7.0 (`mockito-core`) | `mockito-inline` removed: inline mock maker is the default in Mockito 5 |
| spring-security-test | 5.6.1 | 6.2.8 | |
| junit-platform-launcher | (implicit) | 1.10.5 (`testRuntimeOnly`) | Gradle 8 requires it to be declared |
| selenium-java | 4.15.0 (other modules forced to **3.141.59** by the Boot 2.6 BOM) | 4.15.0 (all modules) | `ext['selenium.version'] = '4.15.0'` keeps every module on the same version |
| webdrivermanager | 5.6.2 | 5.9.2 | |
| testng | 7.8.0 | 7.10.2 | |
| extentreports | 5.1.1 | 5.1.2 | |
| httpclient5 | 5.2.1 | 5.2.3 | Boot BOM |

## 3. Test results

| | Before (Java 11 / Boot 2.6.3) | After (Java 17 / Boot 3.2.12) |
|---|---|---|
| Build result | BUILD SUCCESSFUL | BUILD SUCCESSFUL |
| Test classes | 20 | 20 |
| Tests run | 68 | 68 |
| Passed | 68 | 68 |
| Failed | 0 | 0 |
| Errors | 0 | 0 |
| Skipped / `@Disabled` | 0 | 0 |
| Compilation errors | 0 | 0 |
| `javac -Xlint:deprecation,removal,unchecked` warnings | not measured | **0** |
| JaCoCo instruction coverage (gate skipped) | 33.2% (3486 / 10497) | 32.0% (2440 / 7617) |

Coverage note: the gate was already failing before the upgrade. The totals differ because DGS codegen 6 generates fewer and smaller classes into `build/generated`, and JaCoCo counts those classes too. No tests were removed.

Selenium smoke suite (`DISPLAY=:0 ./gradlew seleniumTest`, TestNG, not part of `build`): before, not run; after, 2/2 passed. The suite only checks that the browser and WebDriverManager start (it opens google.com). It does not exercise the app; see TESTING_GAPS.md.

## 4. javax → jakarta migration

- `javax.validation.*` → `jakarta.validation.*` and `javax.servlet.*` → `jakarta.servlet.*` were replaced with find-and-replace (`sed`) across `src/`: 21 files, 39 `jakarta.*` imports. Compilation passed after the replacement, and the build was then run under Spotless.
- `javax.crypto.SecretKey` / `SecretKeySpec` in `DefaultJwtService` are JDK classes with no Jakarta equivalent. They were removed by switching to jjwt 0.12's `Keys.hmacShaKeyFor(...)`, typed as `java.security.Key`.
- Check: `grep -rn "javax" src/` gives **0 matches**.

## 5. Fixes for removed classes, deprecated APIs and changed signatures

| # | File | Problem in Boot 3 / Java 17 | Fix |
|---|---|---|---|
| 1 | `api/security/WebSecurityConfig.java` | `WebSecurityConfigurerAdapter` was removed in Spring Security 6. `antMatchers`, `authorizeRequests` and the `.and()` chain are gone or deprecated. | Replaced with a `SecurityFilterChain` `@Bean` that uses the lambda DSL with `authorizeHttpRequests` and `requestMatchers`. The rules and their order are unchanged. Added `dispatcherTypeMatchers(ERROR, FORWARD).permitAll()`: Security 6 also authorizes ERROR dispatches, so without it 404/422 responses would turn into 401. |
| 2 | `api/exception/CustomizeExceptionHandler.java` | Spring 6 changed `ResponseEntityExceptionHandler.handleMethodArgumentNotValid` to take `HttpStatusCode` instead of `HttpStatus`, so `@Override` no longer compiled. | Signature updated to `HttpStatusCode status`. |
| 3 | `graphql/exception/GraphQLCustomizeExceptionHandler.java` | graphql-java 21 removed `DataFetcherExceptionHandler.onException`. | Now implements `CompletableFuture<DataFetcherExceptionHandlerResult> handleException(...)` and delegates to DGS `DefaultDataFetcherExceptionHandler.handleException`. Also replaced a raw `List` cast (unchecked warning) with `computeIfAbsent`. |
| 4 | `infrastructure/service/DefaultJwtService.java` | In jjwt 0.12, `SignatureAlgorithm`, `setSubject`, `setExpiration`, `parserBuilder`, `setSigningKey`, `parseClaimsJws` and `getBody` are deprecated. | Now uses `subject()`, `expiration()`, `Jwts.parser().keyLocator(...)`, `parseSignedClaims()` and `getPayload()`. The key comes from `Keys.hmacShaKeyFor(secret UTF-8 bytes)`, which still produces HS512 for the 86-byte secret. |
| 5 | `build.gradle` (`generateJava`) | DGS codegen 6 no longer maps the schema's `PageInfo` type to `graphql.relay.PageInfo`. It generates its own `io.spring.graphql.types.PageInfo`, which broke 6 call sites in `ArticleDatafetcher`/`CommentDatafetcher`. | Added `typeMapping = ["PageInfo": "graphql.relay.PageInfo"]` so codegen 5 behaviour and the GraphQL response shape stay the same. |
| 6 | `build.gradle` (`spotless`) | Gradle 8 fails the build because `spotlessJava` scanned `fileTree(rootDir)`, which overlaps the `compileJava`/`generateJava` outputs. Gradle 7.4 already logged this as a warning (see `build-before.log`). | Target narrowed to `src/**/*.java`, the only tracked Java sources. |
| 7 | `build.gradle` (`seleniumTest`) | Gradle 8 deprecates custom `Test` tasks that rely on the default `testClassesDirs`/`classpath`. | Set both explicitly from `sourceSets.test`. |
| 8 | `build.gradle` | Top-level `sourceCompatibility`/`targetCompatibility` are deprecated on Gradle 8. | Moved into a `java { }` block. |
| 9 | `test/.../selenium/pages/BasePage.java` | `new WebDriverWait(driver, long)` was removed in Selenium 4. It only compiled before because the Boot 2.6 BOM downgraded `selenium-api` to 3.141.59. | Now uses `new WebDriverWait(driver, Duration.ofSeconds(...))`. |
| 10 | `README.md`, `AGENTS.md` | Still documented Java 11 / Boot 2.6.3. | Updated to Java 17 / Boot 3.2.12. |

Remaining build warnings (none come from this repo's code):
- `The org.gradle.api.plugins.Convention type has been deprecated` comes from `com.netflix.dgs.codegen` 6.3.0 (`CodegenPlugin.kt:46`). It only matters for Gradle 9 and is not reachable from the build script.
- `OpenJDK ... Sharing is only supported for boot loader classes` is printed by the JVM when Mockito 5's inline mock maker attaches its agent during tests. It is harmless.
- `UserDetailsServiceAutoConfiguration` "generated security password" WARN at startup. This is the same Boot behaviour as before. The app authenticates with JWT and never uses that user.

## 6. Deployability check (Java 17 runtime)

The `bootJar` built here (`build/libs/uc-spring-boot-upgrade-microservice-extraction-0.0.1-SNAPSHOT.jar`) was started with `java -jar` on JDK 17. Output: `Started RealWorldApplication in 2.441 seconds`, with Flyway V1+V2 applied to a fresh SQLite `dev.db`. Results of the smoke checks run with curl:

| Check | Result |
|---|---|
| `GET /tags`, `GET /articles` | 200, seeded data (5 articles) |
| `POST /users/login` (john@example.com) | 200, JWT issued |
| `GET /user` with token / without token / with a bad token | 200 / 401 / 401 |
| `GET /articles/feed` anonymous | 401 |
| `POST /articles` authenticated | 200 |
| `POST /users` with invalid body | 422 `{"errors":{"email":["should be an email"],"username":["can't be empty"]}}` |
| `GET /articles/does-not-exist` | 404 (not 401, which confirms fix #1's ERROR-dispatch rule) |
| GraphQL `tags` + `articles(first:2){pageInfo}` | 200, Relay `pageInfo` serialized (confirms fix #5) |
| GraphQL `me` with token | 200 |
| GraphQL `login` with a wrong password | `UNAUTHENTICATED` typed error (confirms fix #3) |
| CORS preflight `OPTIONS /articles` | 200 |

## 7. OWASP dependency check

`./gradlew dependencyCheckAnalyze` was **not run**: the `org.owasp.dependencycheck` plugin is not configured in `build.gradle`, and `./gradlew tasks --all` lists no `dependencyCheck*` task. There is no report to capture. See TESTING_GAPS.md.

## 8. Diff summary (`asiri-progress...HEAD`)

| Scope | Files changed | Insertions | Deletions |
|---|---|---|---|
| `src/` (Java) | 23 | 96 | 92 |
| Build (`build.gradle`, wrapper jar/properties, `gradlew`, `gradlew.bat`) | 5 | 239 | 155 |
| Docs (`README.md`, `AGENTS.md`) | 2 | 3 | 3 |
| **Code subtotal** | **30** | **338** | **250** |

Deliverable files added on top of that: `MIGRATION_PROOF.md`, `TESTING_GAPS.md`, `build-before.log`, `build-after.log`. Most of the `gradlew`/`gradlew.bat` lines were regenerated by `./gradlew wrapper --gradle-version 8.9`.

## 9. Reproduce

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew clean build -x jacocoTestCoverageVerification
grep -rn "javax" src/            # expect no output
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew bootRun   # or: java -jar build/libs/*-SNAPSHOT.jar
```
