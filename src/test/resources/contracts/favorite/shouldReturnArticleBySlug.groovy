package contracts.favorite

import org.springframework.cloud.contract.spec.Contract

/**
 * ILLUSTRATIVE contract for the Favorite -> Article boundary.
 *
 * Phase 1 extracts the Favorite domain into its own service. That service still needs to resolve an
 * article slug to an article id, which it will do by calling the monolith. This contract pins the
 * part of GET /articles/{slug} the favorite service depends on: the article envelope with id, slug
 * and favoritesCount. It is verified against the monolith (producer) with MockMvc; the generated
 * stub is what the future favorite service (consumer) tests against.
 */
Contract.make {
    description "article envelope used by the favorite service to resolve a slug"
    request {
        method GET()
        url "/articles/contract-article"
        headers {
            accept applicationJson()
        }
    }
    response {
        status OK()
        headers {
            contentType applicationJson()
        }
        body(
                article: [
                        id            : "8ba1e1a0-0a0a-4f9e-8b16-3a1f9d7e0001",
                        slug          : "contract-article",
                        title         : "contract article",
                        favorited     : true,
                        favoritesCount: 3
                ]
        )
        bodyMatchers {
            jsonPath('$.article.id', byType())
            jsonPath('$.article.title', byType())
            jsonPath('$.article.favorited', byType())
            jsonPath('$.article.favoritesCount', byType())
        }
    }
}
