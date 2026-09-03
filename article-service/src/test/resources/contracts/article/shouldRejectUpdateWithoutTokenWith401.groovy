package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT /internal/articles/{id} without a token is 401"
    request {
        method PUT()
        url "/internal/articles/article-1"
        headers {
            contentType applicationJson()
        }
        body(title: "x")
    }
    response {
        status UNAUTHORIZED()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["missing or invalid token"]])
    }
}
