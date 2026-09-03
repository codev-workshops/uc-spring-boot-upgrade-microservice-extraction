package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "one user row (no password or hash) by id; reads take no credentials"
    request {
        method GET()
        url "/internal/users/u1000000-0000-0000-0000-000000000001"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(user: [id: "u1000000-0000-0000-0000-000000000001", username: "john", email: "john@jacob.com", bio: "bio", image: "img"])
    }
}
