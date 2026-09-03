package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/ids/cursor returns up to limit+1 ids for the monolith to compute hasNext"
    request {
        method GET()
        url "/internal/articles/ids/cursor?tag=java&limit=2&direction=next&cursor=1706696130123"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articleIds: ["article-2", "article-3", "article-4"])
    }
}
