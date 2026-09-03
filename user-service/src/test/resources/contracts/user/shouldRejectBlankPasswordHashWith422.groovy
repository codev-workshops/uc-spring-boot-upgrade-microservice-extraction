package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/users without passwordHash is 422"
    request {
        method POST()
        url "/internal/users"
        headers {
            contentType applicationJson()
        }
        body(id: "user-x", username: "x", email: "x@example.com")
    }
    response {
        status UNPROCESSABLE_ENTITY()
        headers {
            contentType applicationJson()
        }
        body(errors: [passwordHash: ["can't be empty"]])
    }
}
