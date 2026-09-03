package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "PUT /internal/articles/{articleId}/tags upserts tags by name with caller-supplied ids and returns the article's tagList"
    request {
        method PUT()
        url "/internal/articles/article-1/tags"
        headers {
            contentType applicationJson()
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
        body(tags: [[id: "tag-1", name: "java"], [id: "tag-2", name: "spring-boot"], [id: "9f1c2a1e-0000-4000-8000-000000000001", name: "new"]])
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(articleId: "article-1", tagList: ["java", "spring-boot", "new"])
    }
}
