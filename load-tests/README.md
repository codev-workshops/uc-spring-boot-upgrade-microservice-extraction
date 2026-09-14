# Load tests (k6)

Two independent [k6](https://k6.io) scripts load-test the REST and GraphQL surfaces
separately. Each script seeds its own data, runs its own scenario and writes its own
pair of reports, so they can be run one at a time and compared side by side.

```
load-tests/
  lib/setup.js       shared helpers (base URL, register/login, seed articles + comments)
  rest-test.js       REST scenario -> reports/rest-summary.json + reports/rest-report.html
  graphql-test.js    GraphQL scenario -> reports/graphql-summary.json + reports/graphql-report.html
  reports/           generated output (git-ignored)
```

## Prerequisites

1. Install k6: https://grafana.com/docs/k6/latest/set-up/install-k6/
   (e.g. `brew install k6`, or download a release binary).
2. Start the app from the repo root in a separate terminal:

   ```
   ./gradlew bootRun
   ```

   It listens on http://localhost:8080 with an empty SQLite `dev.db` (the `bootRun`
   task deletes the DB first). The scripts seed everything they need in `setup()`.
3. The scripts pull `htmlReport` (k6-reporter) and `textSummary` (jslib) via remote
   imports, so the machine running k6 needs outbound internet access on first run.

## Running

From the repo root (paths in `handleSummary` are relative to the working directory):

```
k6 run load-tests/rest-test.js
k6 run load-tests/graphql-test.js
```

Environment overrides:

| Var        | Default                 | Meaning                                  |
|------------|-------------------------|------------------------------------------|
| `BASE_URL` | `http://localhost:8080` | Target application                       |
| `VUS`      | `10`                    | Steady-state virtual users               |
| `DURATION` | `60s`                   | Steady-state duration (plus 10s ramp-up and 5s ramp-down) |

```
BASE_URL=http://localhost:8080 VUS=25 DURATION=2m k6 run load-tests/rest-test.js
BASE_URL=http://localhost:8080 VUS=25 DURATION=2m k6 run load-tests/graphql-test.js
```

Use the same `VUS`/`DURATION` for both runs so the reports are comparable.

## What each script does

**rest-test.js** — mostly reads, weighted like a real client: `GET /articles`
(plain, `?tag=`, `?author=`, `?favorited=`, with `offset`/`limit` paging),
`GET /articles/{slug}`, `GET /articles/{slug}/comments`, `GET /profiles/{username}`,
`GET /tags`, authenticated `GET /articles/feed`, and ~7% writes (create article,
add comment, favorite, follow). Custom metrics `rest_endpoint_latency`,
`rest_endpoint_requests`, `rest_endpoint_errors` are tagged with `endpoint`.

**graphql-test.js** — POSTs to `/graphql`: `articles(first:N)` (plain, `withTag`,
`authoredBy`), authenticated `feed(first:N)`, `profile(username){articles favorites feed}`,
`article(slug)`, `tags`, and authenticated `me`. The connection queries request
`author { username }`, `favorited`, `favoritesCount` and `comments { edges { node { body author { username } } } }`
for every node, and `first` is varied over 5/10/20. Custom metrics
`graphql_operation_latency`, `graphql_operation_requests`, `graphql_errors` are tagged
with `operation` and `page_size`. A response with an `errors` array counts as a failure
even when HTTP status is 200.

## Reading the reports

Each run writes to `load-tests/reports/`:

- `*-report.html` — open in a browser. The "Requests" tab breaks down `http_req_duration`
  per URL/`name` tag; the custom-metrics section lists the per-endpoint / per-operation
  Trends (avg, med, p90, p95, p99, max) and Counters.
- `*-summary.json` — the raw k6 summary (`metrics.<name>.values`, plus `submetrics`
  per tag value) for scripting or diffing across runs.

Compare across the two reports:

1. **p95 latency per endpoint vs per operation** — `rest_endpoint_latency{endpoint:"GET /articles"}`
   against `graphql_operation_latency{operation:"articles"}` (and `feed`, `profile`).
2. **Throughput** — `http_reqs` rate and the per-endpoint/operation Counters, at the same
   VU count.
3. **Growth with page size** — in the GraphQL summary, latency for
   `page_size:"5"` vs `"10"` vs `"20"` on the `articles`/`feed` operations.

### Expected finding

REST `GET /articles` builds the whole page in a small, fixed number of batched queries,
so its latency is roughly flat as `limit` grows. The GraphQL connection queries instead
resolve `Article.author`, `Article.comments` and `Comment.author` per node in the
data fetchers under `src/main/java/io/spring/graphql/` (`ArticleDatafetcher`,
`CommentDatafetcher`, `ProfileDatafetcher`), i.e. an N+1 (really N + N·M) fan-out.
The GraphQL report should therefore show much higher p95 latency and lower
throughput for `articles`/`feed`/`profile` than the REST `/articles` endpoints, with
the gap widening as `first` increases, while leaf operations (`tags`, `me`,
`article(slug)`) stay close to their REST counterparts. That contrast is the reason the
two surfaces are reported separately.
