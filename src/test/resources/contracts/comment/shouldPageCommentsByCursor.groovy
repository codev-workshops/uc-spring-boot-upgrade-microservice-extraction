package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "cursor page: limit+1 probe rows, created_at < cursor for next, millis cursor"
    request {
        method GET()
        urlPath("/internal/articles/a1000000-0000-0000-0000-000000000001/comments/cursor") {
            queryParameters {
                parameter "limit": "1"
                parameter "direction": "next"
                parameter "cursor": "1704240000000"
            }
        }
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(
                comments: [
                        [id: "c2000000-0000-0000-0000-000000000002", body: "second",
                         articleId: "a1000000-0000-0000-0000-000000000001",
                         userId: "u1000000-0000-0000-0000-000000000001",
                         createdAt: "2024-01-02T00:00:00.000Z", updatedAt: "2024-01-02T00:00:00.000Z"],
                        [id: "c1000000-0000-0000-0000-000000000001", body: "first",
                         articleId: "a1000000-0000-0000-0000-000000000001",
                         userId: "u2000000-0000-0000-0000-000000000002",
                         createdAt: "2024-01-01T00:00:00.000Z", updatedAt: "2024-01-01T00:00:00.000Z"]
                ]
        )
    }
}
