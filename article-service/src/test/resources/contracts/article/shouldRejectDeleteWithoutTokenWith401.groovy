package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "DELETE /internal/articles/{id} without a token is 401"
    request {
        method DELETE()
        url "/internal/articles/article-1"
    }
    response {
        status UNAUTHORIZED()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["missing or invalid token"]])
    }
}
