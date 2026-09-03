package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "delete needs a valid token and is idempotent: 204 whether or not the row exists"
    request {
        method DELETE()
        url "/internal/articles/a1000000-0000-0000-0000-000000000001/comments/c1000000-0000-0000-0000-000000000001"
        headers {
            header "Authorization": $(consumer(regex("Token .+")), producer("Token valid-jwt-for-u2"))
        }
    }
    response {
        status NO_CONTENT()
    }
}
