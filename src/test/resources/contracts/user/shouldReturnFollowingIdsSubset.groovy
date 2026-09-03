package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "subset of ids that {id} follows (mirrors followingAuthors)"
    request {
        method GET()
        url "/internal/users/u1000000-0000-0000-0000-000000000001/following?ids=u2000000-0000-0000-0000-000000000002,u3000000-0000-0000-0000-000000000003"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(followingIds: ["u2000000-0000-0000-0000-000000000002"])
    }
}
