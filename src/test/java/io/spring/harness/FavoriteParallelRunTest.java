package io.spring.harness;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.spring.JacksonCustomizations;
import io.spring.api.ArticleApi;
import io.spring.api.ArticleFavoriteApi;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.ArticleQueryService;
import io.spring.application.article.ArticleCommandService;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ProfileData;
import io.spring.application.data.UserData;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.core.service.JwtService;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import java.util.Arrays;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Parallel-run example for the Favorite domain. Both sides of the migration are driven through the
 * same scenarios; in Phase 0 the extracted side does not exist yet, so it is skipped rather than
 * failed. The flag {@code extraction.favorite.enabled} is a test-side property only — no production
 * code reads it.
 */
@WebMvcTest(controllers = {ArticleApi.class, ArticleFavoriteApi.class})
@Import({WebSecurityConfig.class, JacksonCustomizations.class})
@TestPropertySource(properties = "extraction.favorite.enabled=false")
public class FavoriteParallelRunTest {
  @Autowired private MockMvc mvc;

  @Value("${extraction.favorite.enabled}")
  private boolean extractedRouteEnabled;

  @MockBean private UserRepository userRepository;
  @MockBean private UserReadService userReadService;
  @MockBean private JwtService jwtService;
  @MockBean private ArticleQueryService articleQueryService;
  @MockBean private ArticleRepository articleRepository;
  @MockBean private ArticleCommandService articleCommandService;
  @MockBean private ArticleFavoriteRepository articleFavoriteRepository;

  private ParallelRunHarness harness;
  private String token;
  private Article article;

  @BeforeEach
  public void setUp() {
    harness = new ParallelRunHarness(mvc, extractedRouteEnabled);

    User user = new User("reader@test.com", "reader", "123", "", "");
    token = "token";
    when(jwtService.getSubFromToken(eq(token))).thenReturn(Optional.of(user.getId()));
    when(userRepository.findById(eq(user.getId()))).thenReturn(Optional.of(user));
    when(userReadService.findById(eq(user.getId())))
        .thenReturn(new UserData(user.getId(), user.getEmail(), user.getUsername(), "", ""));

    User author = new User("author@test.com", "author", "123", "", "");
    article = new Article("parallel run", "desc", "body", Arrays.asList("java"), author.getId());
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));

    DateTime now = new DateTime();
    ArticleData articleData =
        new ArticleData(
            article.getId(),
            article.getSlug(),
            article.getTitle(),
            article.getDescription(),
            article.getBody(),
            true,
            1,
            now,
            now,
            Arrays.asList("java"),
            new ProfileData(
                author.getId(), author.getUsername(), author.getBio(), author.getImage(), false));
    when(articleQueryService.findBySlug(eq(article.getSlug()), eq(user)))
        .thenReturn(Optional.of(articleData));
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void article_read_envelope_should_match_the_golden(RoutePath route) throws Exception {
    Assumptions.assumeTrue(
        harness.supports(route), () -> route + " route is not available in Phase 0");

    String envelope =
        harness.captureEnvelope(
            route,
            get("/articles/{slug}", article.getSlug()).header("Authorization", "Token " + token));

    harness.assertMatchesGolden("favorite/article-read", envelope);
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void favorite_write_envelope_should_match_the_golden(RoutePath route) throws Exception {
    Assumptions.assumeTrue(
        harness.supports(route), () -> route + " route is not available in Phase 0");

    String envelope =
        harness.captureEnvelope(
            route,
            post("/articles/{slug}/favorite", article.getSlug())
                .header("Authorization", "Token " + token));

    harness.assertMatchesGolden("favorite/favorite-write", envelope);
  }
}
