package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/tags/{name}/article-ids returns distinct article ids in article_tags rowid order"
    request {
        method GET()
        url "/internal/tags/java/article-ids"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articleIds: ["article-1", "article-4", "article-5"])
    }
}
