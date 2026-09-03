package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/{articleId}/comments/cursor returns up to limit+1 rows for the monolith's CursorPager"
    request {
        method GET()
        url("/internal/articles/article-1/comments/cursor") {
            queryParameters {
                parameter "limit": "1"
                parameter "direction": "next"
                parameter "cursor": "1706782530123"
            }
        }
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
