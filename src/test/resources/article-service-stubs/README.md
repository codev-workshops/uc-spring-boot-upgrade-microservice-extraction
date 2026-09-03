# article-service stubs (Tag and Article parts)

JSON envelopes of the canonical internal Tag API of article-service
(`docs/microservice-extraction/phases/phase-3-tag.md` section 2.1), replayed by
`ArticleServiceClientTest` through `MockRestServiceServer`. The same shapes are pinned as consumer
contracts under `../contracts/tag`.

The `article-*.json`, `articles-response.json`, `feed-*.json`, `new-article-request.json` and
`update-article-request.json` files are the Article part
(`docs/microservice-extraction/phases/phase-4-article.md` section 2.1), replayed by
`ArticleDomainServiceClientTest` and pinned under `../contracts/article`.
