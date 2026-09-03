package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "comment rows of an article, created_at DESC, no credentials required"
    request {
        method GET()
        url "/internal/articles/a1000000-0000-0000-0000-000000000001/comments"
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
