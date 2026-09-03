package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/tags?articleIds= still returns an entry (empty tagList) for an article without tags"
    request {
        method GET()
        url "/internal/articles/tags?articleIds=article-9"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articleTags: [[articleId: "article-9", tagList: []]])
    }
}
