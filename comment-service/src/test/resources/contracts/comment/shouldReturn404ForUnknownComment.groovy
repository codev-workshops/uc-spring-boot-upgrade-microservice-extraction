package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/comments/{id} for an unknown id is 404 with the monolith error envelope"
    request {
        method GET()
        url "/internal/comments/missing"
    }
    response {
        status NOT_FOUND()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["comment not found"]])
    }
}
