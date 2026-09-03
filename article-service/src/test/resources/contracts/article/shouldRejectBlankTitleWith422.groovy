package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/articles with a blank title is 422"
    request {
        method POST()
        url "/internal/articles"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(id: "article-9", title: "", description: "d", body: "b", userId: "user-1")
    }
    response {
        status UNPROCESSABLE_ENTITY()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["title can't be empty"]])
    }
}
