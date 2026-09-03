package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST without a token is 401 with the monolith error envelope"
    request {
        method POST()
        url "/internal/articles/article-1/comments"
        headers {
            contentType applicationJson()
        }
        body(id: "comment-x", body: "Nice!", userId: "user-1")
    }
    response {
        status UNAUTHORIZED()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["missing or invalid token"]])
    }
}
