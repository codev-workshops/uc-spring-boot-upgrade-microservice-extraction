# comment-service contracts (monolith as CONSUMER)

Unlike `../favorite`, where the monolith is the producer, these contracts describe what the
monolith's `CommentServiceClient` expects from **comment-service** (the producer): the canonical
internal API of `docs/microservice-extraction/phases/phase-2-comment.md` section 2.1.

They are therefore *excluded* from the monolith's producer verification (`contracts { excludedFiles }`
in `build.gradle`) and are meant to be copied verbatim into
`comment-service/src/test/resources/contracts/` where the generated tests run against the service.
On the monolith side the same envelopes are replayed by `CommentServiceClientTest` from
`src/test/resources/comment-service-stubs`, so both sides pin the identical shape.
