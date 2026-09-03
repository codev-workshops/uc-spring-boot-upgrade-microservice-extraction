package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "batch of user rows by ids (order irrelevant; the monolith never calls with empty ids)"
    request {
        method GET()
        url "/internal/users?ids=u1000000-0000-0000-0000-000000000001,u2000000-0000-0000-0000-000000000002"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(users: [
                [id: "u1000000-0000-0000-0000-000000000001", username: "john", email: "john@jacob.com", bio: "bio", image: "img"],
                [id: "u2000000-0000-0000-0000-000000000002", username: "jane", email: "jane@jacob.com", bio: "", image: "img2"]
        ])
    }
}
