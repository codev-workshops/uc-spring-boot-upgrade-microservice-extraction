package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "unfollow requires the caller JWT (sub == id); 204 and idempotent"
    request {
        method DELETE()
        url "/internal/users/u1000000-0000-0000-0000-000000000001/follows/u2000000-0000-0000-0000-000000000002"
        headers {
            header "Authorization": $(consumer(regex("Token .+")), producer("Token valid-jwt-for-u1"))
        }
    }
    response {
        status NO_CONTENT()
    }
}
