package contracts.favorite

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/favorites/counts returns one entry per requested id, in request order, 0 when none"
    request {
        method POST()
        url "/internal/favorites/counts"
        headers {
            contentType applicationJson()
        }
        body(articleIds: ["article-1", "article-2"])
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(
                counts: [
                        [articleId: "article-1", count: 2],
                        [articleId: "article-2", count: 0]
                ]
        )
    }
}
