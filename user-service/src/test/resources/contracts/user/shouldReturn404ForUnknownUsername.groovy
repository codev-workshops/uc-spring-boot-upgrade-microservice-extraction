package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/users/by-username/{username} for an unknown username is 404"
    request {
        method GET()
        url "/internal/users/by-username/missing"

    }
    response {
        status NOT_FOUND()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["user not found"]])
    }
}
