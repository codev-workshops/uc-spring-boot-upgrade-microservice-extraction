package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/tags returns every tag name in select-name-from-tags (rowid) order"
    request {
        method GET()
        url "/internal/tags"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(tags: ["java", "spring-boot", "web-development", "tutorial"])
    }
}
