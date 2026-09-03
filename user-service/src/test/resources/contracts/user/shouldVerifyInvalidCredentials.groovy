package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/users/{id}/credentials/verify with a wrong password is valid:false"
    request {
        method POST()
        url "/internal/users/user-1/credentials/verify"
        headers {
            contentType applicationJson()
        }
        body(password: "wrong")
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(valid: false)
    }
}
