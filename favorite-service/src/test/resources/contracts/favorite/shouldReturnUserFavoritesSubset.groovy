package contracts.favorite

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/favorites/query returns the subset of requested ids favorited by the user"
    request {
        method POST()
        url "/internal/favorites/query"
        headers {
            contentType applicationJson()
        }
        body(userId: "user-1", articleIds: ["article-1", "article-2"])
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(userId: "user-1", articleIds: ["article-1"])
    }
}
