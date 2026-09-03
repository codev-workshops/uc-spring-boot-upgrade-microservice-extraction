package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "create with the monolith-generated id/createdAt; JWT subject must equal userId; 201 with the row"
    request {
        method POST()
        url "/internal/articles/a1000000-0000-0000-0000-000000000001/comments"
        headers {
            contentType applicationJson()
            header "Authorization": $(consumer(regex("Token .+")), producer("Token valid-jwt-for-u2"))
        }
        body(
                id: "c1000000-0000-0000-0000-000000000001",
                body: "first",
                userId: "u2000000-0000-0000-0000-000000000002",
                createdAt: "2024-01-01T00:00:00.000Z"
        )
    }
    response {
        status CREATED()
        headers { contentType applicationJson() }
        body(
                comment: [id: "c1000000-0000-0000-0000-000000000001", body: "first",
                          articleId: "a1000000-0000-0000-0000-000000000001",
                          userId: "u2000000-0000-0000-0000-000000000002",
                          createdAt: "2024-01-01T00:00:00.000Z", updatedAt: "2024-01-01T00:00:00.000Z"]
        )
    }
}
