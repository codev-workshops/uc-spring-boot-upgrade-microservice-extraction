package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "username held by a different id is 422 with the monolith error envelope"
    request {
        method POST()
        url "/internal/users"
        headers { contentType applicationJson() }
        body(
                id: "u2000000-0000-0000-0000-000000000002",
                username: "john",
                email: "other@jacob.com",
                passwordHash: "\$2a\$10\$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ab",
                bio: "",
                image: "img"
        )
    }
    response {
        status UNPROCESSABLE_ENTITY()
        headers { contentType applicationJson() }
        body(errors: [username: ["duplicated username"]])
    }
}
