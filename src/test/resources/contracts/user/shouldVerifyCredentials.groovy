package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "plain password check against the stored hash; unknown id is {valid:false}, never 404"
    request {
        method POST()
        url "/internal/users/u1000000-0000-0000-0000-000000000001/credentials/verify"
        headers { contentType applicationJson() }
        body(password: "secret")
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(valid: true)
    }
}
