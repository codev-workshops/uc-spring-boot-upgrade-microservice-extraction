package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT with a blank tag name is 422 with the monolith error envelope"
    request {
        method PUT()
        url "/internal/articles/article-1/tags"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(tags: [[id: "tag-1", name: " "]])
    }
    response {
        status UNPROCESSABLE_ENTITY()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["tags[0].name can't be empty"]])
    }
}
