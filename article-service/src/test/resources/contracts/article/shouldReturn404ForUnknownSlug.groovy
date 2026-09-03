package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/by-slug/{slug} is 404 for an unknown slug"
    request {
        method GET()
        url "/internal/articles/by-slug/unknown"
    }
    response {
        status NOT_FOUND()
        headers {
            contentType applicationJson()
        }
        body(errors: [body: ["article not found"]])
    }
}
