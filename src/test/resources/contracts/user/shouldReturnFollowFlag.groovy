package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "single relation check (mirrors isUserFollowing / findRelation)"
    request {
        method GET()
        url "/internal/users/u1000000-0000-0000-0000-000000000001/follows/u2000000-0000-0000-0000-000000000002"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(following: true)
    }
}
