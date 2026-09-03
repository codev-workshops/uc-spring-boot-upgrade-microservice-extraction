package contracts.comment

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "DELETE /internal/articles/{articleId}/comments/{id} returns 204 whether or not the comment existed"
    request {
        method DELETE()
        url "/internal/articles/article-1/comments/comment-1"
        headers {
            header("Authorization", $(consumer(regex("Token .+")), producer("Token contract-token")))
        }
    }
    response {
        status NO_CONTENT()
    }
}
