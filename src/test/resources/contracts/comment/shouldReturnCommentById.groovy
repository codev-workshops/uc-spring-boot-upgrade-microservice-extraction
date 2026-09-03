package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "single comment row wrapped in {comment}; unknown id is 404"
    request {
        method GET()
        url "/internal/comments/c1000000-0000-0000-0000-000000000001"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(
                comment: [id: "c1000000-0000-0000-0000-000000000001", body: "first",
                          articleId: "a1000000-0000-0000-0000-000000000001",
                          userId: "u2000000-0000-0000-0000-000000000002",
                          createdAt: "2024-01-01T00:00:00.000Z", updatedAt: "2024-01-01T00:00:00.000Z"]
        )
    }
}
