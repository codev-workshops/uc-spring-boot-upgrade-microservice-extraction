package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/users with an email owned by another id is 422 with the monolith envelope"
    request {
        method POST()
        url "/internal/users"
        headers {
            contentType applicationJson()
        }
        body(id: "user-dup-mail", username: "other", email: "john@example.com", passwordHash: "hash")
    }
    response {
        status UNPROCESSABLE_ENTITY()
        headers {
            contentType applicationJson()
        }
        body(errors: [email: ["duplicated email"]])
    }
}
