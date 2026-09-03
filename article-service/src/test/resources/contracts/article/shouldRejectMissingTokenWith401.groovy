package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/articles without a token is 401 with the monolith error envelope"
    request {
        method POST()
        url "/internal/articles"
        headers {
            contentType applicationJson()
        }
        body(id: "article-1", slug: "hello-world", title: "Hello World", description: "d", body: "b", userId: "user-1", createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z", tags: [[id: "tag-1", name: "java"], [id: "tag-2", name: "spring-boot"]])
    }
    response {
        status UNAUTHORIZED()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["missing or invalid token"]])
    }
}
