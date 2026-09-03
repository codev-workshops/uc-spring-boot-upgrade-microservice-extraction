package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/users/{id}/following?ids= returns the subset of ids the user follows"
    request {
        method GET()
        url "/internal/users/user-1/following?ids=user-2,user-3"

    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(followingIds: ["user-2"])
    }
}
