package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/feed with no authorIds is an empty feed"
    request {
        method GET()
        url "/internal/articles/feed?authorIds="
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articles: [], count: 0)
    }
}
