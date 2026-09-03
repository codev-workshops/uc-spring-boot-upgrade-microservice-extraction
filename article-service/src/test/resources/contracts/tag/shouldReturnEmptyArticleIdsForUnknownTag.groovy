package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/tags/{name}/article-ids for an unknown tag returns an empty list"
    request {
        method GET()
        url "/internal/tags/unknown/article-ids"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articleIds: [])
    }
}
