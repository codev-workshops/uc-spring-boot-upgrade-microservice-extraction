package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "unknown id/username/email is 404 (the monolith maps it to Optional.empty / public 404)"
    request {
        method GET()
        url "/internal/users/by-username/nope"
        headers { accept applicationJson() }
    }
    response {
        status NOT_FOUND()
    }
}
