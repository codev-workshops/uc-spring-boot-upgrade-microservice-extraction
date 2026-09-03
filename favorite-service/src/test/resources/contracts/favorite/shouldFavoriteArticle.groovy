package contracts.favorite

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT /internal/favorites/{articleId}/{userId} is idempotent and requires a token whose subject is userId"
    request {
        method PUT()
        url "/internal/favorites/article-1/user-1"
        headers {
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articleId: "article-1", userId: "user-1", favorited: true)
    }
}
