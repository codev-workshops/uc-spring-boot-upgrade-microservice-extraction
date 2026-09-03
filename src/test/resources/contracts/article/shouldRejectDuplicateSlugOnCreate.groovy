package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "a slug already used by a different article id is the monolith's duplicate-title 422 envelope"
    request {
        method POST()
        url "/internal/articles"
        headers {
            contentType applicationJson()
            header "Authorization": $(consumer(regex("Token .+")), producer("Token valid-jwt-for-u1"))
        }
        body(
                id: "a9000000-0000-0000-0000-000000000009",
                slug: "java-article",
                title: "java article",
                description: "d",
                body: "b",
                userId: "u1000000-0000-0000-0000-000000000001",
                createdAt: "2024-01-05T00:00:00.000Z",
                tags: []
        )
    }
    response {
        status UNPROCESSABLE_ENTITY()
        headers { contentType applicationJson() }
        body(errors: [title: ["article name exists"]])
    }
}
