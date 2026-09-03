package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "no match yields an empty id page with count 0"
    request {
        method GET()
        url "/internal/articles/ids?tag=nope&offset=0&limit=20"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(articleIds: [], count: 0)
    }
}
