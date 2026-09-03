package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/articles with an already stored id returns the existing row with 200"
    request {
        method POST()
        url "/internal/articles"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(id: "article-existing", slug: "hello-world", title: "Hello World", description: "d", body: "b", userId: "user-1", createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z", tags: [[id: "tag-1", name: "java"], [id: "tag-2", name: "spring-boot"]])
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(article: [id: "article-existing", slug: "hello-world", title: "Hello World", description: "d", body: "b", userId: "user-1", createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z", tagList: ["java", "spring-boot"]])
    }
}
