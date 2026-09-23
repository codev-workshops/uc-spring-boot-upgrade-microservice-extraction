# Phase 4 Test Report — Remove the GraphQL layer

## Scope

Delete the Netflix DGS GraphQL adapter and everything that only existed to support it, leaving the
REST layer (`io.spring.api`) as the sole API. No service, domain or REST code changed apart from the
security rules that only existed for GraphQL.

### Removed

| Item | Detail |
|---|---|
| `src/main/java/io/spring/graphql/**` | `ArticleDatafetcher`, `ArticleMutation`, `CommentDatafetcher`, `CommentMutation`, `MeDatafetcher`, `ProfileDatafetcher`, `RelationMutation`, `TagDatafetcher`, `UserMutation`, `SecurityUtil`, `exception/AuthenticationException`, `exception/GraphQLCustomizeExceptionHandler` |
| `src/main/resources/schema/schema.graphqls` | GraphQL schema (source of the generated `io.spring.graphql.types` package) |
| `build.gradle` | plugin `com.netflix.dgs.codegen 5.0.6`, dependency `com.netflix.graphql.dgs:graphql-dgs-spring-boot-starter:4.9.21`, `tasks.named('generateJava')` codegen configuration |
| `WebSecurityConfig` | `permitAll()` rules for `/graphql` and `/graphiql` |
| `graphql-schema.png`, README "GraphQL Support" section, AGENTS.md purpose line | documentation remnants |

There were no DGS-specific `@Configuration` beans; the DGS auto-configuration came entirely from the
removed starter. Generated types were never checked in — they were produced by the `generateJava`
task into `build/generated`, which no longer exists after `./gradlew clean`.

## Commands and outcomes

| Command | Outcome |
|---|---|
| `./gradlew spotlessApply` | PASS |
| `./gradlew clean build -x jacocoTestCoverageVerification --console=plain` | PASS — **88 total, 88 passed, 0 failed, 0 skipped**; no compilation errors, no stale generated sources |
| `grep -rniE "graphql\|dgs\|graphiql" src build.gradle README.md` | no matches |
| `java -jar build/libs/uc-spring-boot-upgrade-microservice-extraction-0.0.1-SNAPSHOT.jar --server.port=18080` | Boots: `Started RealWorldApplication in 2.206 seconds`; `GET /tags` → 200 with tag list; `GET /articles?first=2` → 200 with `articles`, `startCursor`, `endCursor`, `hasNext`, `hasPrevious`; `POST /graphql` → 401 (no longer served) |

The global JaCoCo threshold is skipped per `AGENTS.md`.

## Functionality → test → result

| Functionality | Test class / method | Result |
|---|---|---|
| Spring application context starts without DGS | `RealworldApplicationTests.contextLoads` (`@SpringBootTest`) | PASS |
| Application boots from the packaged jar and serves REST | manual `java -jar` smoke test (see above) | PASS |
| Security config compiles/works without `/graphql` rules | all `@WebMvcTest` classes importing `WebSecurityConfig` (11 classes, 55 tests) | PASS |
| REST cursor pagination (replacement for GraphQL connections) | `CursorArticlesApiTest` (8), `CursorCommentsApiTest` (5) | PASS |
| REST parity for every former GraphQL operation | tests listed in `phase3-test-report.md` | PASS |
| Service / domain / infrastructure layers untouched | `io.spring.application.*`, `io.spring.core.*`, `io.spring.infrastructure.*` tests (33) | PASS |

Test count is unchanged from Phase 3 (88): the GraphQL layer had no dedicated tests, so nothing was
lost by deleting it.

## Regressions

None. The build compiled cleanly on the first attempt after removal; no code outside
`io.spring.graphql` referenced the removed package or generated types.

## Conclusion

**Phase complete / safe to proceed to Phase 5 (final regression and sign-off).**
