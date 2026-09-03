package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/articles whose userId differs from the token subject is 403"
    request {
        method POST()
        url "/internal/articles"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(id: "article-1", slug: "hello-world", title: "Hello World", description: "d", body: "b", userId: "user-2", createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z", tags: [[id: "tag-1", name: "java"], [id: "tag-2", name: "spring-boot"]])
    }
    response {
        status FORBIDDEN()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["token subject does not match userId"]])
    }
}
