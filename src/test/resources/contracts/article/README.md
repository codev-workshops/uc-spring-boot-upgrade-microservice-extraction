# article-service Article contracts (monolith as CONSUMER)

Like `../tag` and `../comment`, these describe what the monolith's
`ArticleDomainServiceClient` expects from **article-service** (the producer) for the Article
domain: the canonical internal API of `docs/microservice-extraction/phases/phase-4-article.md`
section 2.1 — one contract per endpoint of the table.

They are *excluded* from the monolith's producer verification (`contracts { excludedFiles }` in
`build.gradle`) and are meant to be copied verbatim into
`article-service/src/test/resources/contracts/` where the generated tests run against the service.
On the monolith side the same envelopes are replayed by `ArticleDomainServiceClientTest` from
`src/test/resources/article-service-stubs`, so both sides pin the identical shape.

Promises the monolith relies on: rows never carry author profile, `favorited` or `favoritesCount`
(composed locally); timestamps are ISO-8601 with millis in UTC; `tagList` follows `article_tags`
insertion order; id pages are `DISTINCT ... ORDER BY created_at DESC LIMIT offset,limit` with
`count` = total; cursor endpoints return up to `limit+1` rows/ids and the monolith derives
`hasNext`/`hasPrevious`; reads take no credentials, writes require `Authorization: Token <jwt>`
whose subject equals the row's `userId`.
