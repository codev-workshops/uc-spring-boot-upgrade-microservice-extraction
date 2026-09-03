package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST for a userId different from the token subject is 403 with the monolith error envelope"
    request {
        method POST()
        url "/internal/articles/article-1/comments"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(id: "comment-x", body: "Nice!", userId: "user-2")
    }
    response {
        status FORBIDDEN()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["token subject does not match userId"]])
    }
}
