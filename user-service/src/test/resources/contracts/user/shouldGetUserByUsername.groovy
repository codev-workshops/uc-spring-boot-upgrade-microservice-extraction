package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/users/by-username/{username} returns the user row"
    request {
        method GET()
        url "/internal/users/by-username/johndoe"

    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(user: [id: "user-1", username: "johndoe", email: "john@example.com", bio: "Full-stack developer and tech enthusiast", image: "https://api.dicebear.com/7.x/avataaars/svg?seed=John"])
    }
}
