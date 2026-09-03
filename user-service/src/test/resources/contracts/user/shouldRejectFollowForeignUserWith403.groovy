package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT /internal/users/{id}/follows/{targetId} for an id different from the token subject is 403"
    request {
        method PUT()
        url "/internal/users/user-2/follows/user-1"
        headers {
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
    }
    response {
        status FORBIDDEN()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["token subject does not match id"]])
    }
}
