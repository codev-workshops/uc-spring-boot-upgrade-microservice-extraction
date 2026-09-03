package contracts.favorite

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT for a userId different from the token subject is 403 with the monolith error envelope"
    request {
        method PUT()
        url "/internal/favorites/article-1/user-2"
        headers {
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
    }
    response {
        status FORBIDDEN()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["token subject does not match userId"]])
    }
}
