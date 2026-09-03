package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT /internal/users/{id} with a matching token subject updates non-blank fields and returns the row"
    request {
        method PUT()
        url "/internal/users/user-1"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(username: "", email: null, passwordHash: "", bio: "Updated bio", image: "")
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(user: [id: "user-1", username: "johndoe", email: "john@example.com", bio: "Updated bio", image: "https://api.dicebear.com/7.x/avataaars/svg?seed=John"])
    }
}
