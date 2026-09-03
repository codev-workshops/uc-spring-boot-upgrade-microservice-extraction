package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "distinct ids of the articles carrying a tag"
    request {
        method GET()
        url "/internal/tags/java/article-ids"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(articleIds: ["a1000000-0000-0000-0000-000000000001", "a2000000-0000-0000-0000-000000000002"])
    }
}
