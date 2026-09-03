package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/tags with empty articleIds returns an empty list"
    request {
        method GET()
        url "/internal/articles/tags?articleIds="
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articleTags: [])
    }
}
