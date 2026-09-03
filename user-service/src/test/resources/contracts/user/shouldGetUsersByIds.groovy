package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/users?ids=a,b returns the matching rows"
    request {
        method GET()
        url "/internal/users?ids=user-1,user-2"

    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(users: [[id: "user-1", username: "johndoe", email: "john@example.com", bio: "Full-stack developer and tech enthusiast", image: "https://api.dicebear.com/7.x/avataaars/svg?seed=John"], [id: "user-2", username: "janedoe", email: "jane@example.com", bio: "Software architect passionate about clean code", image: "https://api.dicebear.com/7.x/avataaars/svg?seed=Jane"]])
    }
}
