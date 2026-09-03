package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST with an id that already exists is idempotent: 200 with the stored row"
    request {
        method POST()
        url "/internal/articles/article-1/comments"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(id: "comment-1", body: "Great article!", userId: "user-1", createdAt: "2024-01-31T10:15:30.123Z")
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(
                comment: [id: "comment-1", body: "Great article!", articleId: "article-1", userId: "user-1",
                          createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z"]
        )
    }
}
