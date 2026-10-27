# article-service

The Articles domain (articles, tags, article/tag links), extracted from the Conduit monolith into a standalone Spring Boot 3.2.12 / Java 17 service.

## What it owns

| Area | Owner |
|---|---|
| `articles`, `tags`, `article_tags` tables | article-service (own SQLite file `article-service.db`) |
| users, profiles, follows, favorites, comments | monolith (port 8080) |

Flyway migrations: `V1__create_article_tables.sql` (schema) and `V2__seed_article_data.sql` (the monolith's seed articles/tags, same IDs).

## REST API (port 8081)

The URLs, request bodies and response envelopes are the same as the monolith's:

- `GET /articles?tag=&author=&favorited=&offset=&limit=`
- `GET /articles/feed` (requires auth)
- `POST /articles` (requires auth)
- `GET /articles/{slug}`
- `PUT /articles/{slug}`, `DELETE /articles/{slug}` (requires auth; owner only, otherwise 403)
- `GET /tags`

Errors use the monolith's format: `{"errors": {"field": ["message"]}}`. Validation errors are 422. If the user service can't be reached, the response is 503 with `errors.userService`.

## Cross-service communication

`UserServiceClient` (`infrastructure/client`) calls the monolith's read-only `/internal/**` endpoints with Spring `RestClient`. The DTOs it uses live in `infrastructure/client/dto`.

| Call | Used for | On failure |
|---|---|---|
| `GET /internal/me` | resolve the `Authorization: Token <jwt>` header to a user | 503 (an invalid token is just treated as anonymous) |
| `GET /internal/me/following` | feed | 503 |
| `GET /internal/profiles?ids=…` / `?username=…` | `author` block, `author=` filter | 503 |
| `GET /internal/favorites?articleIds=…` | `favoritesCount`, `favorited` | defaults to 0 / false |
| `GET /internal/favorites/by-user/{username}` | `favorited=` filter | 503 |

The caller's token is forwarded on each request, so the monolith stays the only service that validates JWTs.

Configuration: `user-service.base-url` (env `USER_SERVICE_BASE_URL`, default `http://localhost:8080`), `user-service.connect-timeout`, `user-service.read-timeout`.

## Build and run

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew build          # 24 tests
./gradlew bootRun        # starts with a fresh article-service.db on :8081
```

Run the monolith (`../`, `./gradlew bootRun`) at the same time; it provides authentication and profiles.

## Migration status

This is the first step of a strangler-fig migration. For now, the monolith still serves its own `/articles` endpoints, because comments, favorites and GraphQL read its local `articles` table. The two databases are not kept in sync. Next steps:

1. Route `/articles` and `/tags` to this service, through a gateway or the frontend's API base URL.
2. Change comments and favorites in the monolith to check that an article exists by calling this service, instead of joining on `articles`.
3. Remove the article code and tables from the monolith.
