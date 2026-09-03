package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/users/{id}/credentials/verify with the right password is valid:true"
    request {
        method POST()
        url "/internal/users/user-1/credentials/verify"
        headers {
            contentType applicationJson()
        }
        body(password: "password123")
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(valid: true)
    }
}
