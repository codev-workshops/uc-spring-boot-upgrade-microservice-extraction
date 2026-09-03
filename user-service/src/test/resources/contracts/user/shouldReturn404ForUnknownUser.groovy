package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/users/{id} for an unknown id is 404 with the monolith error envelope"
    request {
        method GET()
        url "/internal/users/missing"

    }
    response {
        status NOT_FOUND()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["user not found"]])
    }
}
