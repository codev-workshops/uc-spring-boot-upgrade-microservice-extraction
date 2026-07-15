# Comments Service Extraction

## Runtime contract

The comments service listens on port `8081` by default and the monolith remains on `8080`.
Both applications must receive the same `JWT_SECRET` environment variable. The comments service
also accepts:

- `MONOLITH_BASE_URL` (default `http://localhost:8080`)
- `COMMENTS_SERVICE_PORT` (default `8081`)

Internal callbacks are authenticated with `X-Internal-Service-Key`, whose value is the shared
`JWT_SECRET`.

### Batch profiles

`POST /internal/profiles/batch`

```json
{
  "viewerId": "user-1",
  "userIds": ["user-2", "user-3"]
}
```

```json
[
  {
    "id": "user-2",
    "username": "janedoe",
    "bio": "Software architect passionate about clean code",
    "image": "https://example.test/avatar.svg",
    "following": true
  }
]
```

The comments service sends one request containing the distinct author ids for each comment page.
If profile hydration fails, the comments are returned with empty profile display fields and
`following=false`.

### Article lookup

`GET /internal/articles/{slug}`

```json
{
  "id": "article-1",
  "authorId": "user-1"
}
```

The comments service uses the response to persist the monolith article id and enforce comment
deletion authorization using ids only. Successful lookups are cached in memory so reads can
continue during a later monolith outage; a cold lookup failure returns a clear `503`.

## API ownership

The comments service owns:

- `POST /articles/{slug}/comments`
- `GET /articles/{slug}/comments`
- `DELETE /articles/{slug}/comments/{id}`

The frontend reads `NEXT_PUBLIC_COMMENTS_SERVICE_BASE_URL` and defaults to
`http://localhost:8081`.

GraphQL comments were removed from the monolith schema. They were not added to the extracted
service because `Article.comments` is an article-owned field that would require a composed
GraphQL gateway or an explicit cross-service resolver. The REST contract remains unchanged.

## Validation commands

```bash
JWT_SECRET=<shared-secret> ./gradlew build -x jacocoTestCoverageVerification
JWT_SECRET=<shared-secret> ./gradlew spotlessCheck

cd comments-service
JWT_SECRET=<shared-secret> ./gradlew build -x jacocoTestCoverageVerification
JWT_SECRET=<shared-secret> ./gradlew spotlessCheck
```

Runtime and end-to-end validation results are recorded in the pull request.
