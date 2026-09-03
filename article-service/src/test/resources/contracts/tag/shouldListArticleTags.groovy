package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "GET /internal/articles/tags?articleIds= returns one entry per requested id with tagList in article_tags rowid order"
    request {
        method GET()
        url "/internal/articles/tags?articleIds=article-1,article-4"
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(
                articleTags: [
                        [articleId: "article-1", tagList: ["java", "spring-boot", "tutorial"]],
                        [articleId: "article-4", tagList: ["java", "spring-boot"]]
                ]
        )
    }
}
