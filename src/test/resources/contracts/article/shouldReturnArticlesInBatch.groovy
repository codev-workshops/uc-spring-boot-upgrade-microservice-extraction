package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "rows of a batch of ids; the monolith re-orders them by the id list it asked for"
    request {
        method GET()
        url "/internal/articles?ids=a1000000-0000-0000-0000-000000000001,a2000000-0000-0000-0000-000000000002"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(articles: [[
                        id: "a1000000-0000-0000-0000-000000000001",
                        slug: "java-article",
                        title: "java article",
                        description: "d1",
                        body: "b1",
                        userId: "u1000000-0000-0000-0000-000000000001",
                        createdAt: "2024-01-03T00:00:00.000Z",
                        updatedAt: "2024-01-03T00:00:00.000Z",
                        tagList: ["java", "spring"]
                ], [
                        id: "a2000000-0000-0000-0000-000000000002",
                        slug: "bare-article",
                        title: "bare article",
                        description: "d2",
                        body: "b2",
                        userId: "u1000000-0000-0000-0000-000000000001",
                        createdAt: "2024-01-01T00:00:00.000Z",
                        updatedAt: "2024-01-01T00:00:00.000Z",
                        tagList: []
                ]])
    }
}
