# comment-service stubs

JSON fixtures for the canonical internal API of comment-service, copied from
`docs/microservice-extraction/phases/phase-2-comment.md` section 2.1. `CommentServiceClientTest`
plays them back through `MockRestServiceServer` so the monolith client is checked against the exact
envelopes the service must produce. Keep them in sync with the provider contracts in
`comment-service/src/test/resources/contracts` and with `src/test/resources/contracts/comment`.
