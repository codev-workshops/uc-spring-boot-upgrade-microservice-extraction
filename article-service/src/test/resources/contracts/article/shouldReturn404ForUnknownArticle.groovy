package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/{id} is 404 for an unknown id"
    request {
        method GET()
        url "/internal/articles/unknown"
    }
    response {
        status NOT_FOUND()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["article not found"]])
    }
}
