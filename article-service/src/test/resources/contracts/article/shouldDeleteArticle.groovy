package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "DELETE /internal/articles/{id} is 204 (idempotent)"
    request {
        method DELETE()
        url "/internal/articles/article-1"
        headers {
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
    }
    response {
        status NO_CONTENT()
    }
}
