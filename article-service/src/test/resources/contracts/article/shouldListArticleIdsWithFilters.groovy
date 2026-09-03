package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/ids returns a page of distinct ids (created_at DESC) plus the total count"
    request {
        method GET()
        url "/internal/articles/ids?tag=java&authorId=user-1&ids=article-1,article-2,article-3&offset=0&limit=2"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articleIds: ["article-1", "article-2"], count: 3)
    }
}
