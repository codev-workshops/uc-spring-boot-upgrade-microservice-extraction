package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "delete removes only the articles row (comments, favorites and article_tags stay, as in the monolith); idempotent"
    request {
        method DELETE()
        url "/internal/articles/a1000000-0000-0000-0000-000000000001"
        headers {
            header "Authorization": $(consumer(regex("Token .+")), producer("Token valid-jwt-for-u1"))
        }
    }
    response {
        status NO_CONTENT()
    }
}
