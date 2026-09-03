package io.spring.article.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.article.api.TagInternalApi;
import io.spring.article.api.exception.CustomizeExceptionHandler;
import io.spring.article.api.security.JwtTokenFilter;
import io.spring.article.application.TagCommandService;
import io.spring.article.application.TagQueryService;
import io.spring.article.application.data.ArticleTagsData;
import io.spring.article.core.service.JwtService;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Base class for the provider-side tests Spring Cloud Contract generates from
 * src/test/resources/contracts. Standalone MockMvc around TagInternalApi with mocked services; the
 * JwtTokenFilter runs with a stub JwtService that accepts the contract token.
 */
public abstract class TagInternalApiContractBase {
  public static final String CONTRACT_TOKEN = "contract-token";
  public static final String USER_ID = "user-1";

  @BeforeEach
  public void setUp() {
    TagQueryService queryService = mock(TagQueryService.class);
    TagCommandService commandService = mock(TagCommandService.class);
    JwtService jwtService = mock(JwtService.class);

    when(jwtService.getSubFromToken(anyString())).thenReturn(Optional.empty());
    when(jwtService.getSubFromToken(CONTRACT_TOKEN)).thenReturn(Optional.of(USER_ID));

    when(queryService.allTags())
        .thenReturn(Arrays.asList("java", "spring-boot", "web-development", "tutorial"));
    when(queryService.findArticleTags(Arrays.asList("article-1", "article-4")))
        .thenReturn(
            Arrays.asList(
                new ArticleTagsData("article-1", Arrays.asList("java", "spring-boot", "tutorial")),
                new ArticleTagsData("article-4", Arrays.asList("java", "spring-boot"))));
    when(queryService.findArticleTags(Arrays.asList("article-9")))
        .thenReturn(
            Collections.singletonList(new ArticleTagsData("article-9", Collections.emptyList())));
    when(queryService.findArticleTags(Collections.emptyList())).thenReturn(Collections.emptyList());
    when(queryService.findArticleIdsByTagName("java"))
        .thenReturn(Arrays.asList("article-1", "article-4", "article-5"));
    when(queryService.findArticleIdsByTagName("unknown")).thenReturn(Collections.emptyList());
    when(commandService.setTags(eq("article-1"), any()))
        .thenReturn(new ArticleTagsData("article-1", Arrays.asList("java", "spring-boot", "new")));

    RestAssuredMockMvc.mockMvc(
        MockMvcBuilders.standaloneSetup(new TagInternalApi(queryService, commandService))
            .setControllerAdvice(new CustomizeExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .addFilters(new JwtTokenFilter(jwtService))
            .build());
  }
}
