# favorite-service consumer stubs

Verbatim request/response bodies of the canonical favorite-service internal API
(`docs/microservice-extraction/phases/phase-1-favorite.md` §2.1), used by the monolith-side
`MockRestServiceServer` tests (`FavoriteServiceClientTest`, `FavoriteExtractedParallelRunTest`).

They live here rather than under `src/test/resources/contracts/` on purpose: that directory is
scanned by the Spring Cloud Contract plugin in MOCKMVC (producer) mode and generates tests that
drive the *monolith's* controllers. The internal API is produced by `favorite-service`, so a
consumer-side stub in the monolith is expressed with `MockRestServiceServer` instead.

| file | endpoint |
|---|---|
| `counts-request.json` / `counts-response.json` | `POST /internal/favorites/counts` |
| `query-request.json` / `query-response.json` | `POST /internal/favorites/query` |
| `by-user-response.json` | `GET /internal/favorites/by-user/{userId}/article-ids` |
| `favorite-response.json` | `PUT /internal/favorites/{articleId}/{userId}` |
| (204, no body) | `DELETE /internal/favorites/{articleId}/{userId}` |
