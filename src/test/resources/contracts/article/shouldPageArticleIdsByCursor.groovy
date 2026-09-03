package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "up to limit+1 ids older than the millisecond cursor for direction=next (created_at < cursor DESC); direction=prev flips both"
    request {
        method GET()
        url "/internal/articles/ids/cursor?tag=java&limit=1&direction=next&cursor=1704326400000"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(articleIds: ["a1000000-0000-0000-0000-000000000001", "a2000000-0000-0000-0000-000000000002"])
    }
}
