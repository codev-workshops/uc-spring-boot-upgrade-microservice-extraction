package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "register with caller-generated id and the monolith BCrypt hash (never the raw password); anonymous, idempotent on id"
    request {
        method POST()
        url "/internal/users"
        headers { contentType applicationJson() }
        body(
                id: "u1000000-0000-0000-0000-000000000001",
                username: "john",
                email: "john@jacob.com",
                passwordHash: $(consumer(regex(/\$2[aby]\$.+/)), producer("\$2a\$10\$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ab")),
                bio: "bio",
                image: "img"
        )
    }
    response {
        status CREATED()
        headers { contentType applicationJson() }
        body(user: [id: "u1000000-0000-0000-0000-000000000001", username: "john", email: "john@jacob.com", bio: "bio", image: "img"])
    }
}
