package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/users/by-email/{email} for an unknown email is 404"
    request {
        method GET()
        url "/internal/users/by-email/missing@example.com"

    }
    response {
        status NOT_FOUND()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["user not found"]])
    }
}
