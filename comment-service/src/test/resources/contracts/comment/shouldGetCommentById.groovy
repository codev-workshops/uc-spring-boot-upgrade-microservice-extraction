package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/comments/{id} returns the wrapped comment row"
    request {
        method GET()
        url "/internal/comments/comment-1"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(
                comment: [id: "comment-1", body: "Great article!", articleId: "article-1", userId: "user-2",
                          createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z"]
        )
    }
}
