package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT /internal/users/{id}/follows/{targetId} returns 204 (idempotent)"
    request {
        method PUT()
        url "/internal/users/user-1/follows/user-2"
        headers {
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
    }
    response {
        status NO_CONTENT()
    }
}
