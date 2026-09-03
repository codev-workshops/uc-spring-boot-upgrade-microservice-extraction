package contracts.favorite

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/favorites/by-user/{userId}/article-ids returns every article id the user favorited"
    request {
        method GET()
        url "/internal/favorites/by-user/user-1/article-ids"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(userId: "user-1", articleIds: ["article-1", "article-2"])
    }
}
