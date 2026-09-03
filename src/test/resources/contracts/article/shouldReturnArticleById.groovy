package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "one article row (no author profile, favorites or counts) by id"
    request {
        method GET()
        url "/internal/articles/a1000000-0000-0000-0000-000000000001"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(article: [
                        id: "a1000000-0000-0000-0000-000000000001",
                        slug: "java-article",
                        title: "java article",
                        description: "d1",
                        body: "b1",
                        userId: "u1000000-0000-0000-0000-000000000001",
                        createdAt: "2024-01-03T00:00:00.000Z",
                        updatedAt: "2024-01-03T00:00:00.000Z",
                        tagList: ["java", "spring"]
                ])
    }
}
