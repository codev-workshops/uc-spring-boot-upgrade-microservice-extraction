package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/users/{id}/follows/{targetId} is following:true when the pair exists"
    request {
        method GET()
        url "/internal/users/user-1/follows/user-2"

    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(following: true)
    }
}
