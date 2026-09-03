package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT /internal/users/{id} without a token is 401"
    request {
        method PUT()
        url "/internal/users/user-1"
        headers {
            contentType applicationJson()
        }
        body(bio: "x")
    }
    response {
        status UNAUTHORIZED()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["missing or invalid token"]])
    }
}
