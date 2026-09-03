package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "unknown comment id is 404 with the standard error envelope"
    request {
        method GET()
        url "/internal/comments/00000000-0000-0000-0000-000000000000"
        headers { accept applicationJson() }
    }
    response {
        status NOT_FOUND()
        headers { contentType applicationJson() }
        body(errors: [comment: ["not found"]])
    }
}
