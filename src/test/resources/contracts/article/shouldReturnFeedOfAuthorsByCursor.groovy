package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "feed rows with limit+1 cursor semantics"
    request {
        method GET()
        url "/internal/articles/feed/cursor?authorIds=u1000000-0000-0000-0000-000000000001&limit=5&direction=next&cursor=1704326400000"
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
