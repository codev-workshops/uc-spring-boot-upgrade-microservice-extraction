package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT /internal/articles/{id} updates the non-blank fields and returns the row"
    request {
        method PUT()
        url "/internal/articles/article-1"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(title: "Hello World", description: "", body: "b")
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(article: [id: "article-1", slug: "hello-world", title: "Hello World", description: "d", body: "b", userId: "user-1", createdAt: "2024-01-31T10:15:30.123Z", updatedAt: "2024-01-31T10:15:30.123Z", tagList: ["java", "spring-boot"]])
    }
}
