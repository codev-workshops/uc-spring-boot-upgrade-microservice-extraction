package io.spring.article.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.article.JacksonCustomizations;
import io.spring.article.api.ArticleInternalApi;
import io.spring.article.api.exception.CustomizeExceptionHandler;
import io.spring.article.api.exception.DuplicatedArticleException;
import io.spring.article.api.exception.ResourceNotFoundException;
import io.spring.article.api.security.JwtTokenFilter;
import io.spring.article.application.ArticleCommandService;
import io.spring.article.application.ArticleQueryService;
import io.spring.article.application.data.ArticleData;
import io.spring.article.application.data.ArticleIdsData;
import io.spring.article.application.data.ArticleListData;
import io.spring.article.core.service.JwtService;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Base class for the provider-side tests generated from src/test/resources/contracts/article.
 * Standalone MockMvc around ArticleInternalApi with mocked services; the JwtTokenFilter runs with a
 * stub JwtService that accepts the contract token for USER_ID.
 */
public abstract class ArticleInternalApiContractBase {
  public static final String CONTRACT_TOKEN = "contract-token";
  public static final String USER_ID = "user-1";
  private static final DateTime T1 = new DateTime(2024, 1, 31, 10, 15, 30, 123, DateTimeZone.UTC);
  private static final DateTime T2 = T1.minusDays(1);

  private static ArticleData row1(String id) {
    return new ArticleData(
        id,
        "hello-world",
        "Hello World",
        "d",
        "b",
        USER_ID,
        T1,
        T1,
        Arrays.asList("java", "spring-boot"));
  }

  private static final ArticleData ROW1 = row1("article-1");
  private static final ArticleData ROW2 =
      new ArticleData(
          "article-2", "second", "Second", "d2", "b2", "user-2", T2, T2, Arrays.asList("sql"));

  @BeforeEach
  public void setUp() {
    ArticleQueryService queryService = mock(ArticleQueryService.class);
    ArticleCommandService commandService = mock(ArticleCommandService.class);
    JwtService jwtService = mock(JwtService.class);

    when(jwtService.getSubFromToken(anyString())).thenReturn(Optional.empty());
    when(jwtService.getSubFromToken(CONTRACT_TOKEN)).thenReturn(Optional.of(USER_ID));

    when(queryService.findById(anyString())).thenReturn(Optional.empty());
    when(queryService.findById("article-1")).thenReturn(Optional.of(ROW1));
    when(queryService.findBySlug(anyString())).thenReturn(Optional.empty());
    when(queryService.findBySlug("hello-world")).thenReturn(Optional.of(ROW1));
    when(queryService.findArticles(Arrays.asList("article-2", "article-1")))
        .thenReturn(Arrays.asList(ROW1, ROW2));
    when(queryService.findArticles(Collections.emptyList())).thenReturn(Collections.emptyList());
    when(queryService.findArticleIds(
            eq("java"),
            eq(USER_ID),
            eq(Arrays.asList("article-1", "article-2", "article-3")),
            argThat(p -> p.getOffset() == 0 && p.getLimit() == 2)))
        .thenReturn(new ArticleIdsData(Arrays.asList("article-1", "article-2"), 3));
    when(queryService.findArticleIdsWithCursor(
            eq("java"),
            isNull(),
            isNull(),
            argThat(
                p ->
                    p.isNext()
                        && p.getQueryLimit() == 3
                        && p.getCursor().getMillis() == 1706696130123L)))
        .thenReturn(Arrays.asList("article-2", "article-3", "article-4"));
    when(queryService.findUserFeed(eq(Arrays.asList(USER_ID, "user-2")), any()))
        .thenReturn(new ArticleListData(Arrays.asList(ROW1, ROW2), 2));
    when(queryService.findUserFeed(eq(Collections.emptyList()), any()))
        .thenReturn(new ArticleListData(Collections.emptyList(), 0));
    when(queryService.findUserFeedWithCursor(
            eq(Arrays.asList(USER_ID, "user-2")),
            argThat(p -> p.isNext() && p.getQueryLimit() == 2 && p.getCursor() == null)))
        .thenReturn(Arrays.asList(ROW1, ROW2));

    when(commandService.create(argThat(a -> a != null && "article-1".equals(a.getId()))))
        .thenReturn(new ArticleCommandService.CreateResult(ROW1, true));
    when(commandService.create(argThat(a -> a != null && "article-existing".equals(a.getId()))))
        .thenReturn(new ArticleCommandService.CreateResult(row1("article-existing"), false));
    when(commandService.create(argThat(a -> a != null && "article-clash".equals(a.getId()))))
        .thenThrow(new DuplicatedArticleException());
    when(commandService.update(eq("article-1"), any(), any(), any())).thenReturn(ROW1);
    when(commandService.update(eq("unknown"), any(), any(), any()))
        .thenThrow(new ResourceNotFoundException());
    when(commandService.update(eq("article-2"), eq("Hello World"), any(), any()))
        .thenThrow(new DuplicatedArticleException());

    ObjectMapper objectMapper =
        new ObjectMapper().registerModule(new JacksonCustomizations.RealWorldModules());
    RestAssuredMockMvc.mockMvc(
        MockMvcBuilders.standaloneSetup(new ArticleInternalApi(queryService, commandService))
            .setControllerAdvice(new CustomizeExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .addFilters(new JwtTokenFilter(jwtService))
            .build());
  }
}
