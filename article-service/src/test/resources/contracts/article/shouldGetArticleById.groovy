package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/{id} returns the raw article row with tagList and ISO-8601 UTC timestamps"
    request {
        method GET()
        url "/internal/articles/article-1"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(article: [id: "article-1", slug: "hello-world", title: "Hello World", description: "d", body: "b", userId: "user-1", createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z", tagList: ["java", "spring-boot"]])
    }
}
