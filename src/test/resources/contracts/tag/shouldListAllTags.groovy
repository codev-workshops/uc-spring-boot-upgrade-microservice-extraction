package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "all tag names in tags table row order, no credentials required"
    request {
        method GET()
        url "/internal/tags"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(tags: ["java", "spring", "sqlite"])
    }
}
