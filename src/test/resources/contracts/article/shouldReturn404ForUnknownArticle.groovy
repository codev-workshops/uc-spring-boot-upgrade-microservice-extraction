package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "unknown id or slug is 404 (the monolith maps it to Optional.empty / public 404)"
    request {
        method GET()
        url "/internal/articles/by-slug/nope"
        headers { accept applicationJson() }
    }
    response {
        status NOT_FOUND()
    }
}
