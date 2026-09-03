package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "distinct ids ORDER BY created_at DESC LIMIT offset,limit plus total count; tag / authorId / ids allow-list are all optional and ANDed"
    request {
        method GET()
        url "/internal/articles/ids?tag=java&authorId=u1000000-0000-0000-0000-000000000001&ids=a1000000-0000-0000-0000-000000000001,a2000000-0000-0000-0000-000000000002&offset=0&limit=20"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(articleIds: ["a1000000-0000-0000-0000-000000000001"], count: 1)
    }
}
