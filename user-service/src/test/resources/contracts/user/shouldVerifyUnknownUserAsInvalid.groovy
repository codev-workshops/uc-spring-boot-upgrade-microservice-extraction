package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/users/{id}/credentials/verify for an unknown id is 200 valid:false, never 404"
    request {
        method POST()
        url "/internal/users/missing/credentials/verify"
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
        body(valid: false)
    }
}
