package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles?ids= with no ids returns an empty list"
    request {
        method GET()
        url "/internal/articles?ids="
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articles: [])
    }
}
