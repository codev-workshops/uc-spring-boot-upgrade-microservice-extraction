package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "create with caller-generated id, slug, createdAt and tag ids so dual-write produces identical rows; the row and its tags are stored in one transaction"
    request {
        method POST()
        url "/internal/articles"
        headers {
            contentType applicationJson()
            header "Authorization": $(consumer(regex("Token .+")), producer("Token valid-jwt-for-u1"))
        }
        body(
                id: "a1000000-0000-0000-0000-000000000001",
                slug: "java-article",
                title: "java article",
                description: "d1",
                body: "b1",
                userId: "u1000000-0000-0000-0000-000000000001",
                createdAt: "2024-01-03T00:00:00.000Z",
                tags: [
                        [id: "t1000000-0000-0000-0000-000000000001", name: "java"],
                        [id: "t2000000-0000-0000-0000-000000000002", name: "spring"]
                ]
        )
    }
    response {
        status CREATED()
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
