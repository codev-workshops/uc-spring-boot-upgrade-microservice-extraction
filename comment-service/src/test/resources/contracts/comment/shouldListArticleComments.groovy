package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/{articleId}/comments returns raw comment rows, created_at DESC, updatedAt == createdAt"
    request {
        method GET()
        url "/internal/articles/article-1/comments"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(
                comments: [
                        [id: "comment-1", body: "Great article!", articleId: "article-1", userId: "user-2",
                         createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z"],
                        [id: "comment-2", body: "Thanks for sharing.", articleId: "article-1", userId: "user-3",
                         createdAt: "2024-01-30T10:15:30.123Z", updatedAt: "2024-01-30T10:15:30.123Z"]
                ]
        )
    }
}
