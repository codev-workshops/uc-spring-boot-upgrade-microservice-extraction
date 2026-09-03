package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/feed returns rows of the given authors plus the feed size"
    request {
        method GET()
        url "/internal/articles/feed?authorIds=user-1,user-2&offset=0&limit=20"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articles: [[id: "article-1", slug: "hello-world", title: "Hello World", description: "d", body: "b", userId: "user-1", createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z", tagList: ["java", "spring-boot"]], [id: "article-2", slug: "second", title: "Second", description: "d2", body: "b2", userId: "user-2", createdAt: "2024-01-30T10:15:30.123Z", updatedAt: "2024-01-30T10:15:30.123Z", tagList: ["sql"]]], count: 2)
    }
}
