package contracts.article

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "update title/description/body/slug; blank fields are skipped and updated_at is intentionally NOT rewritten (ArticleMapper.xml#update quirk)"
    request {
        method PUT()
        url "/internal/articles/a1000000-0000-0000-0000-000000000001"
        headers {
            contentType applicationJson()
            header "Authorization": $(consumer(regex("Token .+")), producer("Token valid-jwt-for-u1"))
        }
        body(title: "java renamed", description: "", body: "b1", slug: "java-renamed")
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(article: [
                        id: "a1000000-0000-0000-0000-000000000001",
                        slug: "java-renamed",
                        title: "java renamed",
                        description: "d1",
                        body: "b1",
                        userId: "u1000000-0000-0000-0000-000000000001",
                        createdAt: "2024-01-03T00:00:00.000Z",
                        updatedAt: "2024-01-03T00:00:00.000Z",
                        tagList: ["java", "spring"]
                ])
    }
}
