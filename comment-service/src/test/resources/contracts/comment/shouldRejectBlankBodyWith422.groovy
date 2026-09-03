package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST with a blank body is 422 with the monolith error envelope"
    request {
        method POST()
        url "/internal/articles/article-1/comments"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(id: "comment-blank", body: "", userId: "user-1")
    }
    response {
        status UNPROCESSABLE_ENTITY()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["body can't be empty"]])
    }
}
