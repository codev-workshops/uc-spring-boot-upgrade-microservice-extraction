package io.spring.harness;

import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.service.JwtService;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.favorite.DualWriteFavoriteCommand;
import io.spring.infrastructure.extraction.favorite.FavoriteServiceClient;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 1 wiring of the parallel-run harness: the same scenarios as {@link FavoriteParallelRunTest}
 * run through the real application against an in-memory database. {@link RoutePath#MONOLITH} runs
 * with the flags OFF; {@link RoutePath#EXTRACTED} runs with {@code
 * extraction.favorite.enabled=true, read=extracted, write=dual-write} against a {@link
 * MockRestServiceServer} stub of favorite-service that answers the canonical internal API. Both
 * envelopes must equal the Phase 0 goldens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:sqlite:build/favorite-extracted-parallel-run.db")
public class FavoriteExtractedParallelRunTest {
  @Autowired private MockMvc mvc;
  @Autowired private ExtractionProperties properties;
  @Autowired private FavoriteServiceClient client;
  @Autowired private DualWriteFavoriteCommand dualWrite;
  @Autowired private UserRepository userRepository;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private MyBatisArticleFavoriteRepository monolithFavorites;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbc;

  private ParallelRunHarness harness;
  private MockRestServiceServer favoriteService;
  private User reader;
  private Article article;
  private String token;

  @BeforeEach
  public void setUp() {
    harness = new ParallelRunHarness(mvc, true);
    cleanTables();
    favoriteService =
        MockRestServiceServer.bindTo(client.getRestTemplate()).ignoreExpectOrder(true).build();

    User author = new User("author@test.com", "author", "123", "", "");
    reader = new User("reader@test.com", "reader", "123", "", "");
    userRepository.save(author);
    userRepository.save(reader);
    article =
        new Article(
            "parallel run", "desc", "body", Collections.singletonList("java"), author.getId());
    articleRepository.save(article);
    token = jwtService.toToken(reader);
    dualWrite.clearPending();
  }

  @AfterEach
  public void tearDown() {
    routeOff();
    cleanTables();
  }

  private void cleanTables() {
    for (String table :
        new String[] {
          "article_favorites", "article_tags", "tags", "articles", "follows", "users"
        }) {
      jdbc.update("delete from " + table);
    }
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void article_read_envelope_should_match_the_golden(RoutePath route) throws Exception {
    Assertions.assertTrue(harness.supports(route));
    monolithFavorites.save(new ArticleFavorite(article.getId(), reader.getId()));
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      stubReads(1);
    }

    String envelope =
        harness.captureEnvelope(
            route,
            get("/articles/{slug}", article.getSlug()).header("Authorization", "Token " + token));

    harness.assertMatchesGolden("favorite/article-read", envelope);
    favoriteService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void favorite_write_envelope_should_match_the_golden(RoutePath route) throws Exception {
    Assertions.assertTrue(harness.supports(route));
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      favoriteService
          .expect(
              once(),
              requestTo(
                  favoriteUrl("/internal/favorites/" + article.getId() + "/" + reader.getId())))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(header("Authorization", "Token " + token))
          .andRespond(
              withSuccess(
                  "{\"articleId\":\""
                      + article.getId()
                      + "\",\"userId\":\""
                      + reader.getId()
                      + "\",\"favorited\":true}",
                  MediaType.APPLICATION_JSON));
    }

    String envelope =
        harness.captureEnvelope(
            route,
            post("/articles/{slug}/favorite", article.getSlug())
                .header("Authorization", "Token " + token));

    harness.assertMatchesGolden("favorite/favorite-write", envelope);
    favoriteService.verify();
    Assertions.assertTrue(monolithFavorites.find(article.getId(), reader.getId()).isPresent());
    Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void monolith_and_extracted_read_envelopes_are_identical() throws Exception {
    monolithFavorites.save(new ArticleFavorite(article.getId(), reader.getId()));
    routeOff();
    String monolith =
        harness.captureEnvelope(
            RoutePath.MONOLITH,
            get("/articles/{slug}", article.getSlug()).header("Authorization", "Token " + token));

    configure(RoutePath.EXTRACTED);
    stubReads(1);
    String extracted =
        harness.captureEnvelope(
            RoutePath.EXTRACTED,
            get("/articles/{slug}", article.getSlug()).header("Authorization", "Token " + token));

    harness.assertEnvelopesMatch(monolith, extracted);
  }

  @Test
  public void mirror_failure_does_not_surface_and_is_recorded() throws Exception {
    configure(RoutePath.EXTRACTED);
    favoriteService
        .expect(
            once(),
            requestTo(favoriteUrl("/internal/favorites/" + article.getId() + "/" + reader.getId())))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators
                .withServerError());

    String envelope =
        harness.captureEnvelope(
            RoutePath.EXTRACTED,
            post("/articles/{slug}/favorite", article.getSlug())
                .header("Authorization", "Token " + token));

    harness.assertMatchesGolden("favorite/favorite-write", envelope);
    Assertions.assertEquals(1, dualWrite.pendingMirrorOperations().size());
    Assertions.assertTrue(monolithFavorites.find(article.getId(), reader.getId()).isPresent());
  }

  private void stubReads(int count) {
    favoriteService
        .expect(manyTimes(), requestTo(favoriteUrl("/internal/favorites/query")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"userId\":\""
                    + reader.getId()
                    + "\",\"articleIds\":[\""
                    + article.getId()
                    + "\"]}",
                MediaType.APPLICATION_JSON));
    favoriteService
        .expect(manyTimes(), requestTo(favoriteUrl("/internal/favorites/counts")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"counts\":[{\"articleId\":\""
                    + article.getId()
                    + "\",\"count\":"
                    + count
                    + "}]}",
                MediaType.APPLICATION_JSON));
  }

  private String favoriteUrl(String path) {
    return properties.getFavorite().getBaseUrl() + path;
  }

  private void configure(RoutePath route) {
    DomainRoute favorite = properties.getFavorite();
    if (route == RoutePath.EXTRACTED) {
      favorite.setEnabled(true);
      favorite.setRead(ReadMode.EXTRACTED);
      favorite.setWrite(WriteMode.DUAL_WRITE);
    } else {
      routeOff();
    }
  }

  private void routeOff() {
    DomainRoute favorite = properties.getFavorite();
    favorite.setEnabled(false);
    favorite.setRead(ReadMode.MONOLITH);
    favorite.setWrite(WriteMode.MONOLITH);
  }
}
