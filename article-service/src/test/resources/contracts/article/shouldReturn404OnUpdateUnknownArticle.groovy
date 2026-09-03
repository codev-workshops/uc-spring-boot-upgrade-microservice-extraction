package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT /internal/articles/{id} is 404 for an unknown id"
    request {
        method PUT()
        url "/internal/articles/unknown"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(title: "x")
    }
    response {
        status NOT_FOUND()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["article not found"]])
    }
}
