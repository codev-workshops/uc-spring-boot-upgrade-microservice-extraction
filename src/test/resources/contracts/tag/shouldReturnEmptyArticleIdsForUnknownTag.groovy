package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "an unknown tag is not an error: 200 with an empty id list"
    request {
        method GET()
        url "/internal/tags/no-such-tag/article-ids"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(articleIds: [])
    }
}
