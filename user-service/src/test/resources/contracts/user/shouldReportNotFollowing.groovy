package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/users/{id}/follows/{targetId} is following:false when the pair is absent"
    request {
        method GET()
        url "/internal/users/user-1/follows/user-3"

    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(following: false)
    }
}
