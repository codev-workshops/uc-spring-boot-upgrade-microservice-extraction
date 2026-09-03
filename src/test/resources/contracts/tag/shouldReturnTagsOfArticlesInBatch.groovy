package contracts.tag

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "tag lists of a batch of articles; an article without tags has an empty tagList"
    request {
        method GET()
        url "/internal/articles/tags?articleIds=a1000000-0000-0000-0000-000000000001,a2000000-0000-0000-0000-000000000002"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(
                articleTags: [
                        [articleId: "a1000000-0000-0000-0000-000000000001", tagList: ["java", "spring"]],
                        [articleId: "a2000000-0000-0000-0000-000000000002", tagList: []]
                ]
        )
    }
}
