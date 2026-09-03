package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "ids {id} follows (mirrors followedUsers, follows rowid order)"
    request {
        method GET()
        url "/internal/users/u1000000-0000-0000-0000-000000000001/followed"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(followedIds: ["u2000000-0000-0000-0000-000000000002"])
    }
}
