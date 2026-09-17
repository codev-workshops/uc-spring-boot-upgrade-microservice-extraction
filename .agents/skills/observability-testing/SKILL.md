---
name: test-conduit-observability
description: Run local end-to-end actuator, request correlation, and mutation metric checks for the Conduit Spring Boot backend.
---

# Local Conduit observability testing

## Runtime setup
- Run from `/home/ubuntu/repos/uc-spring-boot-upgrade-microservice-extraction` (or the current clone root).
- Use Java 11: `JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./gradlew bootRun > /tmp/conduit-bootrun.log 2>&1`.
- Default backend listens on 8080 and uses repository-local SQLite `dev.db`; frontend is unnecessary for API/actuator checks.
- Check port 8080 before starting. Reuse a matching backend if its output is accessible; coordinate before stopping an existing process.
- Prefer a file-backed stdout capture you own. Tool overflow files from other sessions may be stale snapshots, and their shell IDs may be inaccessible.
- Wait for `Started RealWorldApplication` and the listening port, rather than assuming startup after a fixed delay.

## Exercise the real endpoints
- Browser: navigate directly to `http://localhost:8080/actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus`.
- Chrome JSON viewer supports Pretty-print; `Ctrl+=` zooms the content for readable evidence.
- Register a unique local username/email through POST `/users`, then POST `/users/login`; both use a `{"user": {...}}` envelope.
- Use the login JWT via `Authorization: Token <jwt>` for POST `/articles`, PUT `/articles/{slug}`, PUT `/user`. Article payloads use the `article` envelope.
- Compare Prometheus counters before and after each operation, not just their presence. Counters can be absent until first use and reset on process restart. Check `application="realworld"`.
- Hidden actuator endpoints may return 401 anonymously because security executes before endpoint mapping. Test with a valid JWT as well to distinguish authentication rejection from actual non-exposure (404).
- Test rejected mutations leave counters unchanged.

## Correlation evidence
- GET `/articles?limit=1` triggers MyBatis request DEBUG logs in the default configuration.
- Verify supplied X-Correlation-Id, fallback X-Request-Id, primary precedence, and generated UUID against both response header and parsed JSON log fields.
- Gradle stdout forwarding can lag the HTTP response. Preserve response IDs and re-read the log after output arrives before concluding that correlation fields are missing.
- Avoid sharing full mutation debug logs, passwords, or JWTs. Extract representative SELECT/Total log lines and redact sensitive responses in evidence.

## Devin Secrets Needed
- None for local runtime checks; register disposable test credentials and keep the resulting JWT in memory.
