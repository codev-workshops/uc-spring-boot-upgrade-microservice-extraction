package io.spring.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.JacksonCustomizations;
import io.spring.api.ArticleApi;
import io.spring.application.ArticleQueryService;
import io.spring.application.article.ArticleCommandService;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ProfileData;
import io.spring.core.article.ArticleRepository;
import io.spring.core.user.User;
import java.util.Arrays;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Base class for the tests Spring Cloud Contract generates from src/test/resources/contracts. The
 * producer side is driven with standalone MockMvc, so verifying the contracts never needs a running
 * service or a database.
 */
public abstract class FavoriteContractBase {
  protected static final String CONTRACT_SLUG = "contract-article";

  @BeforeEach
  public void setUp() {
    ArticleQueryService articleQueryService = mock(ArticleQueryService.class);
    ArticleRepository articleRepository = mock(ArticleRepository.class);
    ArticleCommandService articleCommandService = mock(ArticleCommandService.class);

    when(articleQueryService.findBySlug(eq(CONTRACT_SLUG), any()))
        .thenReturn(Optional.of(articleData()));

    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JacksonCustomizations.RealWorldModules());
    MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
    converter.setObjectMapper(objectMapper);

    // A built MockMvc is handed over instead of a builder so rest-assured does not auto-apply the
    // spring-security-test configurer, which needs a full application context.
    RestAssuredMockMvc.mockMvc(
        MockMvcBuilders.standaloneSetup(
                new ArticleApi(articleQueryService, articleRepository, articleCommandService))
            .setMessageConverters(converter)
            .build());
  }

  private ArticleData articleData() {
    User author = new User("author@test.com", "author", "123", "", "");
    DateTime now = new DateTime();
    return new ArticleData(
        "8ba1e1a0-0a0a-4f9e-8b16-3a1f9d7e0001",
        CONTRACT_SLUG,
        "contract article",
        "desc",
        "body",
        true,
        3,
        now,
        now,
        Arrays.asList("java"),
        new ProfileData(
            "8ba1e1a0-0a0a-4f9e-8b16-3a1f9d7e0002",
            author.getUsername(),
            author.getBio(),
            author.getImage(),
            false));
  }
}
