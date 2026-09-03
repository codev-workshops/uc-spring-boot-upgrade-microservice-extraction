package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/users/{id}/followed returns everyone the user follows"
    request {
        method GET()
        url "/internal/users/user-1/followed"

    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(followedIds: ["user-2"])
    }
}
