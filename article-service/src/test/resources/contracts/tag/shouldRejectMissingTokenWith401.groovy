package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT without a token is 401 with the monolith error envelope"
    request {
        method PUT()
        url "/internal/articles/article-1/tags"
        headers {
            contentType applicationJson()
        }
        body(tags: [[id: "tag-1", name: "java"]])
    }
    response {
        status UNAUTHORIZED()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["missing or invalid token"]])
    }
}
