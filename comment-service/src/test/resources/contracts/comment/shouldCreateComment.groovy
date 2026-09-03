package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/articles/{articleId}/comments stores the caller-supplied id/createdAt and returns 201"
    request {
        method POST()
        url "/internal/articles/article-1/comments"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(id: "comment-new", body: "Nice!", userId: "user-1", createdAt: "2024-01-31T10:15:30.123Z")
    }
    response {
        status CREATED()
        headers {
            contentType applicationJson()
        }
        body(
                comment: [id: "comment-new", body: "Nice!", articleId: "article-1", userId: "user-1",
                          createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z"]
        )
    }
}
