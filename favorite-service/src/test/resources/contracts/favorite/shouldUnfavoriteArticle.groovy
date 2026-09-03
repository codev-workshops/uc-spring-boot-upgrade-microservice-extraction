package contracts.favorite

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "DELETE /internal/favorites/{articleId}/{userId} returns 204 whether or not the favorite existed"
    request {
        method DELETE()
        url "/internal/favorites/article-1/user-1"
        headers {
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
    }
    response {
        status NO_CONTENT()
    }
}
