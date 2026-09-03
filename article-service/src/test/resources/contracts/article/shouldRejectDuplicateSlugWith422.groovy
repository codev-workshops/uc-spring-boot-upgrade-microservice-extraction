package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/articles with a slug owned by another id is 422 with the title error envelope"
    request {
        method POST()
        url "/internal/articles"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(id: "article-clash", slug: "hello-world", title: "Hello World", description: "d", body: "b", userId: "user-1", createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z", tags: [[id: "tag-1", name: "java"], [id: "tag-2", name: "spring-boot"]])
    }
    response {
        status UNPROCESSABLE_ENTITY()
        headers {
            contentType applicationJson()
        }
        body(errors: [title: ["article name exists"]])
    }
}
