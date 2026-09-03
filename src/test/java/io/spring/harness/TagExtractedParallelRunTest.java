package io.spring.harness;

import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.spring.infrastructure.extraction.favorite.FavoriteServiceClient;
import io.spring.infrastructure.extraction.tag.ArticleServiceClient;
import io.spring.infrastructure.extraction.tag.DualWriteTagCommand;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 3 parallel-run harness for the Tag seam. {@link RoutePath#MONOLITH} runs with the flag OFF
 * (legacy SQL joins); {@link RoutePath#EXTRACTED} runs with {@code extraction.tag.enabled=true,
 * read=extracted, write=dual-write} against a {@link MockRestServiceServer} stub of article-service
 * answering the canonical internal Tag API (phase-3-tag.md section 2.1) with the rows the monolith
 * holds. Both sides must produce the goldens under {@code golden/tag}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:sqlite:build/tag-extracted-parallel-run.db")
public class TagExtractedParallelRunTest {
  @Autowired private MockMvc mvc;
  @Autowired private ExtractionProperties properties;
  @Autowired private ArticleServiceClient client;
  @Autowired private FavoriteServiceClient favoriteClient;
  @Autowired private DualWriteTagCommand dualWrite;
  @Autowired private UserRepository userRepository;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private MyBatisArticleFavoriteRepository favoriteRepository;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbc;

  private final ObjectMapper json = new ObjectMapper();
  private ParallelRunHarness harness;
  private MockRestServiceServer articleService;
  private MockRestServiceServer favoriteService;
  private User author;
  private User reader;
  private Article javaArticle;
  private Article springArticle;
  private Article bareArticle;
  private String readerToken;

  @BeforeEach
  public void setUp() {
    harness = new ParallelRunHarness(mvc, true);
    cleanTables();
    articleService =
        MockRestServiceServer.bindTo(client.getRestTemplate()).ignoreExpectOrder(true).build();
    favoriteService =
        MockRestServiceServer.bindTo(favoriteClient.getRestTemplate())
            .ignoreExpectOrder(true)
            .build();

    author = new User("author@test.com", "author", "123", "bio", "img");
    reader = new User("reader@test.com", "reader", "123", "", "");
    userRepository.save(author);
    userRepository.save(reader);
    // one tag per article: Article de-dups tags through a HashSet, so multi-tag order is not
    // stable in the monolith itself and cannot be pinned by a golden
    javaArticle = article("java article", Collections.singletonList("java"), 2024, 1, 3);
    springArticle = article("spring article", Collections.singletonList("spring"), 2024, 1, 2);
    bareArticle = article("bare article", Collections.emptyList(), 2024, 1, 1);
    articleRepository.save(javaArticle);
    articleRepository.save(springArticle);
    articleRepository.save(bareArticle);
    favoriteRepository.save(new ArticleFavorite(javaArticle.getId(), reader.getId()));
    favoriteRepository.save(new ArticleFavorite(bareArticle.getId(), reader.getId()));
    readerToken = jwtService.toToken(reader);
    dualWrite.clearPending();
  }

  @AfterEach
  public void tearDown() {
    routeOff();
    cleanTables();
  }

  private Article article(String title, List<String> tags, int year, int month, int day) {
    return new Article(
        title,
        "desc",
        "body",
        tags,
        author.getId(),
        new DateTime(year, month, day, 0, 0, DateTimeZone.UTC));
  }

  private void cleanTables() {
    for (String table :
        new String[] {
          "article_favorites", "comments", "article_tags", "tags", "articles", "follows", "users"
        }) {
      jdbc.update("delete from " + table);
    }
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void tags_envelope_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      stubAllTags();
    }

    String envelope = harness.captureEnvelope(route, get("/tags"));

    harness.assertMatchesGolden("tag/tags", envelope);
    articleService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void articles_by_tag_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      stubArticleIdsByTag("java", javaArticle.getId());
      stubArticleTags();
    }

    String envelope =
        harness.captureEnvelope(
            route, get("/articles?tag=java").header("Authorization", "Token " + readerToken));

    harness.assertMatchesGolden("tag/articles-by-tag", envelope);
    articleService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void unknown_tag_should_match_the_golden_and_skip_the_batch(RoutePath route)
      throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      stubArticleIdsByTag("nope");
    }

    String envelope = harness.captureEnvelope(route, get("/articles?tag=nope"));

    harness.assertMatchesGolden("tag/articles-unknown-tag", envelope);
    articleService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void article_list_tag_lists_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      stubArticleTags();
    }

    String envelope =
        harness.captureEnvelope(
            route, get("/articles").header("Authorization", "Token " + readerToken));

    harness.assertMatchesGolden("tag/articles-list", envelope);
    articleService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void article_by_slug_tag_list_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      stubArticleTags();
    }

    String tagged = harness.captureEnvelope(route, get("/articles/{slug}", javaArticle.getSlug()));
    String bare = harness.captureEnvelope(route, get("/articles/{slug}", bareArticle.getSlug()));

    harness.assertMatchesGolden("tag/article-by-slug", tagged);
    harness.assertMatchesGolden("tag/article-by-slug-no-tags", bare);
    articleService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void tag_and_favorited_filters_compose_with_favorite_routed_through_sql(RoutePath route)
      throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      stubArticleIdsByTag("java", javaArticle.getId());
      stubArticleTags();
    }

    String envelope =
        harness.captureEnvelope(
            route,
            get("/articles?tag=java&favorited=reader")
                .header("Authorization", "Token " + readerToken));
    String none = harness.captureEnvelope(route, get("/articles?tag=java&favorited=author"));

    harness.assertMatchesGolden("tag/articles-by-tag-and-favorited", envelope);
    harness.assertMatchesGolden("tag/articles-unknown-tag", none);
    articleService.verify();
  }

  @Test
  public void tag_and_favorited_filters_compose_when_both_domains_are_extracted() throws Exception {
    configure(RoutePath.EXTRACTED);
    DomainRoute favorite = properties.getFavorite();
    favorite.setEnabled(true);
    favorite.setRead(ReadMode.EXTRACTED);
    try {
      stubArticleIdsByTag("java", javaArticle.getId());
      stubArticleTags();
      favoriteService
          .expect(
              manyTimes(),
              requestTo(
                  favorite.getBaseUrl()
                      + "/internal/favorites/by-user/"
                      + reader.getId()
                      + "/article-ids"))
          .andRespond(
              withSuccess(
                  "{\"userId\":\""
                      + reader.getId()
                      + "\",\"articleIds\":[\""
                      + javaArticle.getId()
                      + "\",\""
                      + bareArticle.getId()
                      + "\"]}",
                  MediaType.APPLICATION_JSON));
      favoriteService
          .expect(manyTimes(), requestTo(favorite.getBaseUrl() + "/internal/favorites/query"))
          .andRespond(
              withSuccess(
                  "{\"userId\":\""
                      + reader.getId()
                      + "\",\"articleIds\":[\""
                      + javaArticle.getId()
                      + "\"]}",
                  MediaType.APPLICATION_JSON));
      favoriteService
          .expect(manyTimes(), requestTo(favorite.getBaseUrl() + "/internal/favorites/counts"))
          .andRespond(
              withSuccess(
                  "{\"counts\":[{\"articleId\":\"" + javaArticle.getId() + "\",\"count\":1}]}",
                  MediaType.APPLICATION_JSON));

      String envelope =
          harness.captureEnvelope(
              RoutePath.EXTRACTED,
              get("/articles?tag=java&favorited=reader")
                  .header("Authorization", "Token " + readerToken));

      harness.assertMatchesGolden("tag/articles-by-tag-and-favorited", envelope);
      articleService.verify();
    } finally {
      favorite.setEnabled(false);
      favorite.setRead(ReadMode.MONOLITH);
    }
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void create_with_duplicate_tags_should_match_the_golden_and_dual_write(RoutePath route)
      throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      articleService
          .expect(once(), requestTo(matchesArticleTagsPut()))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(header("Authorization", "Token " + readerToken))
          .andExpect(jsonPath("$.tags.length()").value(1))
          .andExpect(jsonPath("$.tags[0].name").value("java"))
          .andExpect(jsonPath("$.tags[0].id").exists())
          .andRespond(withNoContent());
      // the response is read back locally in the same request (read-after-write marker): no batch
    }

    MvcResult result =
        mvc.perform(
                post("/articles")
                    .header("Authorization", "Token " + readerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"article\":{\"title\":\"created\",\"description\":\"d\",\"body\":\"b\",\"tagList\":[\"java\",\"java\"]}}"))
            .andExpect(status().isOk())
            .andReturn();

    harness.assertMatchesGolden(
        "tag/article-create", harness.normalize(result.getResponse().getContentAsString()));
    articleService.verify();
    Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from article_tags AT join articles A on A.id = AT.article_id where A.slug = 'created'",
            Integer.class));
    Assertions.assertEquals(
        1, jdbc.queryForObject("select count(*) from tags where name = 'java'", Integer.class));
    Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void mirror_failure_does_not_surface_and_is_recorded() throws Exception {
    configure(RoutePath.EXTRACTED);
    articleService
        .expect(once(), requestTo(matchesArticleTagsPut()))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withServerError());

    mvc.perform(
            post("/articles")
                .header("Authorization", "Token " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"article\":{\"title\":\"created\",\"description\":\"d\",\"body\":\"b\",\"tagList\":[\"java\"]}}"))
        .andExpect(status().isOk());

    articleService.verify();
    Assertions.assertEquals(1, dualWrite.pendingMirrorOperations().size());
    Assertions.assertEquals(
        Collections.singletonList("java"),
        dualWrite.pendingMirrorOperations().get(0).getTagNames());
    Assertions.assertEquals(
        1,
        jdbc.queryForObject("select count(*) from articles where slug = 'created'", Integer.class));
  }

  @Test
  public void monolith_and_extracted_envelopes_are_identical() throws Exception {
    routeOff();
    String monolithTags = harness.captureEnvelope(RoutePath.MONOLITH, get("/tags"));
    String monolithList = harness.captureEnvelope(RoutePath.MONOLITH, get("/articles"));

    configure(RoutePath.EXTRACTED);
    stubAllTags();
    stubArticleTags();
    String extractedTags = harness.captureEnvelope(RoutePath.EXTRACTED, get("/tags"));
    String extractedList = harness.captureEnvelope(RoutePath.EXTRACTED, get("/articles"));

    harness.assertEnvelopesMatch(monolithTags, extractedTags);
    harness.assertEnvelopesMatch(monolithList, extractedList);
  }

  @Test
  public void extracted_read_falls_back_to_the_monolith_when_the_service_is_down()
      throws Exception {
    configure(RoutePath.EXTRACTED);
    articleService
        .expect(manyTimes(), requestTo(tagUrl("/internal/tags")))
        .andRespond(withServerError());
    articleService
        .expect(manyTimes(), requestTo(matchesArticleTagsBatch()))
        .andRespond(withServerError());

    String tags = harness.captureEnvelope(RoutePath.EXTRACTED, get("/tags"));
    String bySlug =
        harness.captureEnvelope(
            RoutePath.EXTRACTED, get("/articles/{slug}", javaArticle.getSlug()));

    harness.assertMatchesGolden("tag/tags", tags);
    harness.assertMatchesGolden("tag/article-by-slug", bySlug);
  }

  private void stubAllTags() {
    List<String> names = jdbc.queryForList("select name from tags", String.class);
    articleService
        .expect(manyTimes(), requestTo(tagUrl("/internal/tags")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                write(Collections.singletonMap("tags", names)), MediaType.APPLICATION_JSON));
  }

  /** Answers any batch with the rows the monolith holds (the client filters by requested id). */
  private void stubArticleTags() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select A.id as articleId, T.name as name from articles A"
                + " left join article_tags AT on AT.article_id = A.id"
                + " left join tags T on T.id = AT.tag_id order by A.rowid, AT.rowid");
    StringBuilder body = new StringBuilder("{\"articleTags\":[");
    String current = null;
    for (Map<String, Object> row : rows) {
      String id = (String) row.get("articleId");
      if (!id.equals(current)) {
        if (current != null) {
          body.append("]},");
        }
        body.append("{\"articleId\":\"").append(id).append("\",\"tagList\":[");
        current = id;
        if (row.get("name") != null) {
          body.append('"').append(row.get("name")).append('"');
        }
      } else {
        body.append(",\"").append(row.get("name")).append('"');
      }
    }
    if (current != null) {
      body.append("]}");
    }
    body.append("]}");
    articleService
        .expect(manyTimes(), requestTo(matchesArticleTagsBatch()))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(body.toString(), MediaType.APPLICATION_JSON));
  }

  private void stubArticleIdsByTag(String tag, String... ids) {
    articleService
        .expect(manyTimes(), requestTo(tagUrl("/internal/tags/" + tag + "/article-ids")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                write(Collections.singletonMap("articleIds", Arrays.asList(ids))),
                MediaType.APPLICATION_JSON));
  }

  private org.hamcrest.Matcher<String> matchesArticleTagsBatch() {
    return org.hamcrest.Matchers.startsWith(tagUrl("/internal/articles/tags?articleIds="));
  }

  private org.hamcrest.Matcher<String> matchesArticleTagsPut() {
    return org.hamcrest.Matchers.allOf(
        org.hamcrest.Matchers.startsWith(tagUrl("/internal/articles/")),
        org.hamcrest.Matchers.endsWith("/tags"));
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String tagUrl(String path) {
    return properties.getTag().getBaseUrl() + path;
  }

  private void configure(RoutePath route) {
    DomainRoute tag = properties.getTag();
    if (route == RoutePath.EXTRACTED) {
      tag.setEnabled(true);
      tag.setRead(ReadMode.EXTRACTED);
      tag.setWrite(WriteMode.DUAL_WRITE);
    } else {
      routeOff();
    }
  }

  private void routeOff() {
    DomainRoute tag = properties.getTag();
    tag.setEnabled(false);
    tag.setRead(ReadMode.MONOLITH);
    tag.setWrite(WriteMode.MONOLITH);
  }
}
