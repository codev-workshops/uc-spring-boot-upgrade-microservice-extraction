package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/by-slug/{slug} returns the raw article row"
    request {
        method GET()
        url "/internal/articles/by-slug/hello-world"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(article: [id: "article-1", slug: "hello-world", title: "Hello World", description: "d", body: "b", userId: "user-1", createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z", tagList: ["java", "spring-boot"]])
    }
}
