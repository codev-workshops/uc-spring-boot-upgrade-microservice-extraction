package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "one user row by username"
    request {
        method GET()
        url "/internal/users/by-username/john"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(user: [id: "u1000000-0000-0000-0000-000000000001", username: "john", email: "john@jacob.com", bio: "bio", image: "img"])
    }
}
