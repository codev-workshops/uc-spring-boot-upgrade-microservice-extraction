package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT /internal/articles/{id} to a slug owned by another article is 422 with the title error envelope"
    request {
        method PUT()
        url "/internal/articles/article-2"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(title: "Hello World")
    }
    response {
        status UNPROCESSABLE_ENTITY()
        headers {
            contentType applicationJson()
        }
        body(errors: [title: ["article name exists"]])
    }
}
