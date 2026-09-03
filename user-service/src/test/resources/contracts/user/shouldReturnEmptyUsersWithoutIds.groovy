package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/users without ids returns an empty list"
    request {
        method GET()
        url "/internal/users"

    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(users: [])
    }
}
