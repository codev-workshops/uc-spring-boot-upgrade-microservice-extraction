# article-service Tag contracts (monolith as CONSUMER)

Like `../comment`, these describe what the monolith's `ArticleServiceClient` expects from
**article-service** (the producer) for the Tag domain: the canonical internal API of
`docs/microservice-extraction/phases/phase-3-tag.md` section 2.1.

They are *excluded* from the monolith's producer verification (`contracts { excludedFiles }` in
`build.gradle`) and are meant to be copied verbatim into
`article-service/src/test/resources/contracts/` where the generated tests run against the service.
On the monolith side the same envelopes are replayed by `ArticleServiceClientTest` from
`src/test/resources/article-service-stubs`, so both sides pin the identical shape.

Ordering promises the monolith relies on: `GET /internal/tags` returns `tags` rows in table order
(the monolith's `select name from tags` has no `ORDER BY`); `tagList` follows `article_tags`
insertion order; `articleIds` are distinct.
