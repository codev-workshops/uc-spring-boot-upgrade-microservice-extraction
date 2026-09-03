package io.spring.harness;

import static org.springframework.test.web.client.ExpectedCount.between;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager.Direction;
import io.spring.application.Page;
import io.spring.application.article.ArticleIdPage;
import io.spring.application.article.ArticleRowPage;
import io.spring.application.data.ArticleRow;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.service.JwtService;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.article.ArticleDomainServiceClient;
import io.spring.infrastructure.extraction.article.DualWriteArticleCommand;
import io.spring.infrastructure.extraction.article.LocalArticleQueryAdapter;
import io.spring.infrastructure.extraction.article.PendingArticleMirrorOperation;
import io.spring.infrastructure.extraction.tag.ArticleServiceClient;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Phase 4 parallel-run harness for the Article seam. {@link RoutePath#MONOLITH} runs with the flag
 * OFF (legacy {@code ArticleReadService} joins); {@link RoutePath#EXTRACTED} runs with {@code
 * extraction.article.enabled=true, read=extracted, write=dual-write} against a {@link
 * MockRestServiceServer} fake of article-service that answers every read endpoint of the canonical
 * internal API (phase-4-article.md section 2.1) from the rows the monolith holds — through the very
 * {@link LocalArticleQueryAdapter} SQL, so the fake has the same ordering/paging semantics the
 * service must implement. Both sides must produce the goldens under {@code golden/article}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:sqlite:build/article-extracted-parallel-run.db")
public class ArticleExtractedParallelRunTest {
  @Autowired private MockMvc mvc;
  @Autowired private ExtractionProperties properties;
  @Autowired private ArticleDomainServiceClient client;
  @Autowired private ArticleServiceClient tagClient;
  @Autowired private LocalArticleQueryAdapter localRows;
  @Autowired private DualWriteArticleCommand dualWrite;
  @Autowired private UserRepository userRepository;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private MyBatisArticleFavoriteRepository favoriteRepository;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbc;

  private final ObjectMapper json = new ObjectMapper();
  private ParallelRunHarness harness;
  private MockRestServiceServer articleService;
  private MockRestServiceServer tagService;
  private User author;
  private User other;
  private User reader;
  private Article javaArticle;
  private Article springArticle;
  private Article bareArticle;
  private Article otherArticle;
  private String readerToken;
  private String authorToken;

  @BeforeEach
  public void setUp() {
    harness = new ParallelRunHarness(mvc, true);
    cleanTables();
    articleService =
        MockRestServiceServer.bindTo(client.getRestTemplate()).ignoreExpectOrder(true).build();
    tagService =
        MockRestServiceServer.bindTo(tagClient.getRestTemplate()).ignoreExpectOrder(true).build();

    author = new User("author@test.com", "author", "123", "bio", "img");
    other = new User("other@test.com", "other", "123", "", "");
    reader = new User("reader@test.com", "reader", "123", "", "");
    userRepository.save(author);
    userRepository.save(other);
    userRepository.save(reader);
    userRepository.saveRelation(new FollowRelation(reader.getId(), author.getId()));
    // one tag per article: Article de-dups tags through a HashSet, so multi-tag order is not
    // stable in the monolith itself and cannot be pinned by a golden
    javaArticle = article(author, "java article", Collections.singletonList("java"), 2024, 1, 3);
    springArticle =
        article(author, "spring article", Collections.singletonList("spring"), 2024, 1, 2);
    bareArticle = article(author, "bare article", Collections.emptyList(), 2024, 1, 1);
    otherArticle = article(other, "other article", Collections.singletonList("java"), 2024, 1, 4);
    articleRepository.save(javaArticle);
    articleRepository.save(springArticle);
    articleRepository.save(bareArticle);
    articleRepository.save(otherArticle);
    favoriteRepository.save(new ArticleFavorite(javaArticle.getId(), reader.getId()));
    favoriteRepository.save(new ArticleFavorite(bareArticle.getId(), reader.getId()));
    readerToken = jwtService.toToken(reader);
    authorToken = jwtService.toToken(author);
    dualWrite.clearPending();
  }

  @AfterEach
  public void tearDown() {
    routeOff();
    cleanTables();
  }

  private Article article(User user, String title, List<String> tags, int y, int m, int d) {
    return new Article(
        title, "desc", "body", tags, user.getId(), new DateTime(y, m, d, 0, 0, DateTimeZone.UTC));
  }

  private void cleanTables() {
    for (String table :
        new String[] {
          "article_favorites", "comments", "article_tags", "tags", "articles", "follows", "users"
        }) {
      jdbc.update("delete from " + table);
    }
  }

  // ---------------------------------------------------------------- reads

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void article_by_slug_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);

    String tagged =
        harness.captureEnvelope(
            route,
            get("/articles/{slug}", javaArticle.getSlug())
                .header("Authorization", "Token " + readerToken));
    String bare = harness.captureEnvelope(route, get("/articles/{slug}", bareArticle.getSlug()));

    harness.assertMatchesGolden("article/article-by-slug", tagged);
    harness.assertMatchesGolden("article/article-by-slug-anonymous-no-tags", bare);
    articleService.verify();
    tagService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void unknown_slug_is_404_on_both_routes(RoutePath route) throws Exception {
    configure(route);
    mvc.perform(get("/articles/nope")).andExpect(status().isNotFound());
    mvc.perform(
            put("/articles/nope")
                .header("Authorization", "Token " + authorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"article\":{\"title\":\"x\"}}"))
        .andExpect(status().isNotFound());
    mvc.perform(post("/articles/nope/favorite").header("Authorization", "Token " + readerToken))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void article_list_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);

    String all =
        harness.captureEnvelope(
            route, get("/articles").header("Authorization", "Token " + readerToken));
    String page = harness.captureEnvelope(route, get("/articles?offset=1&limit=2"));

    harness.assertMatchesGolden("article/articles-list", all);
    harness.assertMatchesGolden("article/articles-offset-1-limit-2", page);
    articleService.verify();
    tagService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void article_filters_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);

    String byTag = harness.captureEnvelope(route, get("/articles?tag=java"));
    String byAuthor =
        harness.captureEnvelope(
            route, get("/articles?author=author").header("Authorization", "Token " + readerToken));
    String byFavorited = harness.captureEnvelope(route, get("/articles?favorited=reader"));
    String combined =
        harness.captureEnvelope(
            route,
            get("/articles?tag=java&author=author&favorited=reader")
                .header("Authorization", "Token " + readerToken));
    String noneFavorited = harness.captureEnvelope(route, get("/articles?favorited=author"));

    harness.assertMatchesGolden("article/articles-by-tag", byTag);
    harness.assertMatchesGolden("article/articles-by-author", byAuthor);
    harness.assertMatchesGolden("article/articles-by-favorited", byFavorited);
    harness.assertMatchesGolden("article/articles-by-tag-author-favorited", combined);
    harness.assertMatchesGolden("article/articles-empty", noneFavorited);
    articleService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void unknown_author_yields_an_empty_page_without_any_remote_call(RoutePath route)
      throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      // replace the fake with a strict "nothing may be called" server
      articleService = MockRestServiceServer.bindTo(client.getRestTemplate()).build();
      articleService.expect(never(), requestTo(Matchers.any(String.class)));
    }

    String envelope = harness.captureEnvelope(route, get("/articles?author=ghost"));
    String favorited = harness.captureEnvelope(route, get("/articles?favorited=ghost"));

    harness.assertMatchesGolden("article/articles-empty", envelope);
    harness.assertMatchesGolden("article/articles-empty", favorited);
    articleService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void feed_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);

    String feed =
        harness.captureEnvelope(
            route, get("/articles/feed").header("Authorization", "Token " + readerToken));
    String page =
        harness.captureEnvelope(
            route,
            get("/articles/feed?offset=1&limit=1").header("Authorization", "Token " + readerToken));
    String empty =
        harness.captureEnvelope(
            route, get("/articles/feed").header("Authorization", "Token " + authorToken));

    harness.assertMatchesGolden("article/feed", feed);
    harness.assertMatchesGolden("article/feed-offset-1-limit-1", page);
    harness.assertMatchesGolden("article/articles-empty", empty);
    articleService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void graphql_cursor_list_and_feed_should_match_the_golden(RoutePath route)
      throws Exception {
    configure(route);
    long javaMillis = javaArticle.getCreatedAt().getMillis();
    long springMillis = springArticle.getCreatedAt().getMillis();

    String firstPage = harness.normalize(graphql(articlesQuery("first: 2")));
    String afterJava =
        harness.normalize(graphql(articlesQuery("first: 5, after: \"" + javaMillis + "\"")));
    String beforeSpring =
        harness.normalize(graphql(articlesQuery("last: 5, before: \"" + springMillis + "\"")));
    String byTag = harness.normalize(graphql(articlesQuery("first: 5, withTag: \"java\"")));
    String feedFirst = harness.normalize(graphql(feedQuery("first: 1")));
    String feedAfter =
        harness.normalize(graphql(feedQuery("first: 5, after: \"" + javaMillis + "\"")));

    harness.assertMatchesGolden("article/graphql-articles-first-page", firstPage);
    harness.assertMatchesGolden("article/graphql-articles-after-java", afterJava);
    harness.assertMatchesGolden("article/graphql-articles-before-spring", beforeSpring);
    harness.assertMatchesGolden("article/graphql-articles-by-tag", byTag);
    harness.assertMatchesGolden("article/graphql-feed-first-page", feedFirst);
    harness.assertMatchesGolden("article/graphql-feed-after-java", feedAfter);
    articleService.verify();
  }

  @Test
  public void monolith_and_extracted_envelopes_are_identical() throws Exception {
    routeOff();
    String monolithList =
        harness.captureEnvelope(
            RoutePath.MONOLITH,
            get("/articles?tag=java").header("Authorization", "Token " + readerToken));
    String monolithFeed =
        harness.captureEnvelope(
            RoutePath.MONOLITH,
            get("/articles/feed").header("Authorization", "Token " + readerToken));
    String monolithGraphql = harness.normalize(graphql(articlesQuery("first: 10")));

    configure(RoutePath.EXTRACTED);
    String extractedList =
        harness.captureEnvelope(
            RoutePath.EXTRACTED,
            get("/articles?tag=java").header("Authorization", "Token " + readerToken));
    String extractedFeed =
        harness.captureEnvelope(
            RoutePath.EXTRACTED,
            get("/articles/feed").header("Authorization", "Token " + readerToken));
    String extractedGraphql = harness.normalize(graphql(articlesQuery("first: 10")));

    harness.assertEnvelopesMatch(monolithList, extractedList);
    harness.assertEnvelopesMatch(monolithFeed, extractedFeed);
    harness.assertEnvelopesMatch(monolithGraphql, extractedGraphql);
  }

  @Test
  public void extracted_read_falls_back_to_the_monolith_when_the_service_is_down()
      throws Exception {
    configure(RoutePath.EXTRACTED);
    articleService = MockRestServiceServer.bindTo(client.getRestTemplate()).build();
    articleService
        .expect(manyTimes(), requestTo(Matchers.startsWith(articleUrl("/internal/articles"))))
        .andRespond(withServerError());

    String bySlug =
        harness.captureEnvelope(
            RoutePath.EXTRACTED,
            get("/articles/{slug}", javaArticle.getSlug())
                .header("Authorization", "Token " + readerToken));
    String list =
        harness.captureEnvelope(
            RoutePath.EXTRACTED, get("/articles").header("Authorization", "Token " + readerToken));

    harness.assertMatchesGolden("article/article-by-slug", bySlug);
    harness.assertMatchesGolden("article/articles-list", list);
  }

  @Test
  public void article_reads_on_do_not_call_the_tag_seam_even_when_tag_reads_are_on()
      throws Exception {
    configure(RoutePath.EXTRACTED);
    DomainRoute tag = properties.getTag();
    tag.setEnabled(true);
    tag.setRead(ReadMode.EXTRACTED);
    tagService = MockRestServiceServer.bindTo(tagClient.getRestTemplate()).build();
    tagService.expect(never(), requestTo(Matchers.any(String.class)));
    try {
      String envelope = harness.captureEnvelope(RoutePath.EXTRACTED, get("/articles?tag=java"));
      harness.assertMatchesGolden("article/articles-by-tag", envelope);
      tagService.verify();
    } finally {
      tag.setEnabled(false);
      tag.setRead(ReadMode.MONOLITH);
    }
  }

  // ---------------------------------------------------------------- writes

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void create_should_match_the_golden_and_dual_write_the_row_with_tags(RoutePath route)
      throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      articleService
          .expect(once(), requestTo(articleUrl("/internal/articles")))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("Authorization", "Token " + readerToken))
          .andExpect(jsonPath("$.id").exists())
          .andExpect(jsonPath("$.slug").value("created"))
          .andExpect(jsonPath("$.userId").value(reader.getId()))
          .andExpect(jsonPath("$.createdAt").exists())
          .andExpect(jsonPath("$.tags.length()").value(1))
          .andExpect(jsonPath("$.tags[0].name").value("java"))
          .andExpect(jsonPath("$.tags[0].id").exists())
          .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON));
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
        "article/article-create", harness.normalize(result.getResponse().getContentAsString()));
    articleService.verify();
    Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from article_tags AT join articles A on A.id = AT.article_id where A.slug = 'created'",
            Integer.class));
    Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void create_with_article_and_tag_dual_write_sends_tags_only_in_the_article_post()
      throws Exception {
    configure(RoutePath.EXTRACTED);
    DomainRoute tag = properties.getTag();
    tag.setEnabled(true);
    tag.setWrite(WriteMode.DUAL_WRITE);
    tagService = MockRestServiceServer.bindTo(tagClient.getRestTemplate()).build();
    tagService.expect(never(), requestTo(Matchers.any(String.class)));
    articleService
        .expect(once(), requestTo(articleUrl("/internal/articles")))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.tags[0].name").value("java"))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON));
    try {
      mvc.perform(
              post("/articles")
                  .header("Authorization", "Token " + readerToken)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"article\":{\"title\":\"created\",\"description\":\"d\",\"body\":\"b\",\"tagList\":[\"java\"]}}"))
          .andExpect(status().isOk());
      articleService.verify();
      tagService.verify();
    } finally {
      tag.setEnabled(false);
      tag.setWrite(WriteMode.MONOLITH);
    }
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void duplicate_title_is_422_on_both_routes(RoutePath route) throws Exception {
    configure(route);

    mvc.perform(
            post("/articles")
                .header("Authorization", "Token " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"article\":{\"title\":\"java article\",\"description\":\"d\",\"body\":\"b\",\"tagList\":[]}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.errors.title[0]").value("article name exists"));
    articleService.verify();
    Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from articles where slug = 'java-article'", Integer.class));
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void update_regenerates_the_slug_and_mirrors_the_row(RoutePath route) throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      articleService
          .expect(once(), requestTo(articleUrl("/internal/articles/" + javaArticle.getId())))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(header("Authorization", "Token " + authorToken))
          .andExpect(jsonPath("$.title").value("java renamed"))
          .andExpect(jsonPath("$.slug").value("java-renamed"))
          .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    }

    MvcResult result =
        mvc.perform(
                put("/articles/{slug}", javaArticle.getSlug())
                    .header("Authorization", "Token " + authorToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"article\":{\"title\":\"java renamed\"}}"))
            .andExpect(status().isOk())
            .andReturn();

    harness.assertMatchesGolden(
        "article/article-update", harness.normalize(result.getResponse().getContentAsString()));
    articleService.verify();
    Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from articles where slug = 'java-renamed'", Integer.class));
    Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void only_the_author_may_update_or_delete(RoutePath route) throws Exception {
    configure(route);
    mvc.perform(
            put("/articles/{slug}", javaArticle.getSlug())
                .header("Authorization", "Token " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"article\":{\"title\":\"hijack\"}}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            delete("/articles/{slug}", javaArticle.getSlug())
                .header("Authorization", "Token " + readerToken))
        .andExpect(status().isForbidden());
    Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from articles where slug = 'java-article'", Integer.class));
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void delete_removes_only_the_article_row_and_mirrors_the_delete(RoutePath route)
      throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      articleService
          .expect(once(), requestTo(articleUrl("/internal/articles/" + javaArticle.getId())))
          .andExpect(method(HttpMethod.DELETE))
          .andExpect(header("Authorization", "Token " + authorToken))
          .andRespond(withNoContent());
    }

    mvc.perform(
            delete("/articles/{slug}", javaArticle.getSlug())
                .header("Authorization", "Token " + authorToken))
        .andExpect(status().isNoContent());

    articleService.verify();
    Assertions.assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from articles where id = ?", Integer.class, javaArticle.getId()));
    Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from article_favorites where article_id = ?",
            Integer.class,
            javaArticle.getId()));
    Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from article_tags where article_id = ?",
            Integer.class,
            javaArticle.getId()));
  }

  @Test
  public void mirror_failure_does_not_surface_and_is_recorded() throws Exception {
    configure(RoutePath.EXTRACTED);
    articleService
        .expect(once(), requestTo(articleUrl("/internal/articles")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withServerError());

    mvc.perform(
            post("/articles")
                .header("Authorization", "Token " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"article\":{\"title\":\"created\",\"description\":\"d\",\"body\":\"b\",\"tagList\":[\"java\"]}}"))
        .andExpect(status().isOk());

    articleService.verify();
    List<PendingArticleMirrorOperation> pending = dualWrite.pendingMirrorOperations();
    Assertions.assertEquals(1, pending.size());
    Assertions.assertEquals(PendingArticleMirrorOperation.Kind.CREATE, pending.get(0).getKind());
    Assertions.assertEquals(
        1,
        jdbc.queryForObject("select count(*) from articles where slug = 'created'", Integer.class));
  }

  // ---------------------------------------------------------------- cross-domain slug lookups

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void favorite_and_comment_on_slug_should_match_the_golden(RoutePath route)
      throws Exception {
    configure(route);

    MvcResult favorite =
        mvc.perform(
                post("/articles/{slug}/favorite", springArticle.getSlug())
                    .header("Authorization", "Token " + readerToken))
            .andExpect(status().isOk())
            .andReturn();
    MvcResult comment =
        mvc.perform(
                post("/articles/{slug}/comments", springArticle.getSlug())
                    .header("Authorization", "Token " + readerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":{\"body\":\"nice\"}}"))
            .andExpect(status().isCreated())
            .andReturn();

    harness.assertMatchesGolden(
        "article/favorite-on-slug", harness.normalize(favorite.getResponse().getContentAsString()));
    harness.assertMatchesGolden(
        "article/comment-on-slug", harness.normalize(comment.getResponse().getContentAsString()));
    articleService.verify();
  }

  @Test
  public void extracted_writes_resolve_slugs_through_the_article_service() throws Exception {
    configure(RoutePath.EXTRACTED);
    properties.getArticle().setWrite(WriteMode.EXTRACTED);

    mvc.perform(
            post("/articles/{slug}/favorite", springArticle.getSlug())
                .header("Authorization", "Token " + readerToken))
        .andExpect(status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.article.favorited").value(true));
    mvc.perform(
            post("/articles/{slug}/comments", springArticle.getSlug())
                .header("Authorization", "Token " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":{\"body\":\"nice\"}}"))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/articles/{slug}/favorite", "nope")
                .header("Authorization", "Token " + readerToken))
        .andExpect(status().isNotFound());
    mvc.perform(
            delete("/articles/{slug}", springArticle.getSlug())
                .header("Authorization", "Token " + readerToken))
        .andExpect(status().isForbidden());
    articleService.verify();
    Assertions.assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from comments where article_id = ?",
            Integer.class,
            springArticle.getId()));
  }

  // ---------------------------------------------------------------- fake article-service

  /**
   * Answers every canonical read endpoint from the monolith's own rows via {@link
   * LocalArticleQueryAdapter}; reads must carry no Authorization header.
   */
  private void fakeArticleService() {
    articleService
        .expect(
            between(0, Integer.MAX_VALUE),
            requestTo(Matchers.startsWith(articleUrl("/internal/articles"))))
        .andExpect(method(HttpMethod.GET))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(
            request -> {
              URI uri = request.getURI();
              String path = uri.getPath();
              MultiValueMap<String, String> params =
                  UriComponentsBuilder.fromUri(uri).build().getQueryParams();
              Object body;
              if (path.startsWith("/internal/articles/by-slug/")) {
                Optional<ArticleRow> row =
                    localRows.findBySlug(
                        decode(path.substring("/internal/articles/by-slug/".length())));
                if (!row.isPresent()) {
                  return new MockClientHttpResponse(new byte[0], HttpStatus.NOT_FOUND);
                }
                body = Collections.singletonMap("article", row(row.get()));
              } else if (path.equals("/internal/articles/ids")) {
                ArticleIdPage page =
                    localRows.queryArticleIds(
                        first(params, "tag"),
                        first(params, "authorId"),
                        list(params, "ids"),
                        new Page(
                            Integer.parseInt(first(params, "offset")),
                            Integer.parseInt(first(params, "limit"))));
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("articleIds", page.getArticleIds());
                map.put("count", page.getCount());
                body = map;
              } else if (path.equals("/internal/articles/ids/cursor")) {
                body =
                    Collections.singletonMap(
                        "articleIds",
                        localRows.queryArticleIdsWithCursor(
                            first(params, "tag"),
                            first(params, "authorId"),
                            list(params, "ids"),
                            cursor(params)));
              } else if (path.equals("/internal/articles/feed")) {
                ArticleRowPage page =
                    localRows.findArticlesOfAuthors(
                        list(params, "authorIds"),
                        new Page(
                            Integer.parseInt(first(params, "offset")),
                            Integer.parseInt(first(params, "limit"))));
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("articles", rows(page.getArticles()));
                map.put("count", page.getCount());
                body = map;
              } else if (path.equals("/internal/articles/feed/cursor")) {
                body =
                    Collections.singletonMap(
                        "articles",
                        rows(
                            localRows.findArticlesOfAuthorsWithCursor(
                                list(params, "authorIds"), cursor(params))));
              } else if (path.equals("/internal/articles")) {
                body =
                    Collections.singletonMap(
                        "articles", rows(localRows.findArticles(list(params, "ids"))));
              } else {
                Optional<ArticleRow> row =
                    localRows.findById(path.substring("/internal/articles/".length()));
                if (!row.isPresent()) {
                  return new MockClientHttpResponse(new byte[0], HttpStatus.NOT_FOUND);
                }
                body = Collections.singletonMap("article", row(row.get()));
              }
              MockClientHttpResponse response =
                  new MockClientHttpResponse(json.writeValueAsBytes(body), HttpStatus.OK);
              response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
              return response;
            });
  }

  private static String first(MultiValueMap<String, String> params, String name) {
    String value = params.getFirst(name);
    return value == null ? null : decode(value);
  }

  private static List<String> list(MultiValueMap<String, String> params, String name) {
    String value = first(params, name);
    return value == null ? null : new ArrayList<>(Arrays.asList(value.split(",")));
  }

  private static CursorPageParameter<DateTime> cursor(MultiValueMap<String, String> params) {
    String cursor = first(params, "cursor");
    return new CursorPageParameter<>(
        cursor == null ? null : new DateTime(Long.parseLong(cursor), DateTimeZone.UTC),
        Integer.parseInt(first(params, "limit")),
        "prev".equals(first(params, "direction")) ? Direction.PREV : Direction.NEXT);
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<Map<String, Object>> rows(List<ArticleRow> rows) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (ArticleRow row : rows) {
      result.add(row(row));
    }
    return result;
  }

  private Map<String, Object> row(ArticleRow row) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", row.getId());
    map.put("slug", row.getSlug());
    map.put("title", row.getTitle());
    map.put("description", row.getDescription());
    map.put("body", row.getBody());
    map.put("userId", row.getUserId());
    map.put("createdAt", ISODateTimeFormat.dateTime().withZoneUTC().print(row.getCreatedAt()));
    map.put("updatedAt", ISODateTimeFormat.dateTime().withZoneUTC().print(row.getUpdatedAt()));
    map.put("tagList", row.getTagList());
    return map;
  }

  // ---------------------------------------------------------------- helpers

  private String graphql(String query) throws Exception {
    Map<String, String> body = new HashMap<>();
    body.put("query", query);
    return mvc.perform(
            post("/graphql")
                .header("Authorization", "Token " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static final String ARTICLE_FIELDS =
      "{ pageInfo { hasNextPage hasPreviousPage startCursor endCursor }"
          + " edges { cursor node { slug title description body tagList favorited favoritesCount"
          + " createdAt updatedAt author { username bio image following } } } }";

  private static String articlesQuery(String args) {
    return "{ articles(" + args + ") " + ARTICLE_FIELDS + " }";
  }

  private static String feedQuery(String args) {
    return "{ feed(" + args + ") " + ARTICLE_FIELDS + " }";
  }

  private String articleUrl(String path) {
    return properties.getArticle().getBaseUrl() + path;
  }

  private void configure(RoutePath route) {
    DomainRoute article = properties.getArticle();
    if (route == RoutePath.EXTRACTED) {
      article.setEnabled(true);
      article.setRead(ReadMode.EXTRACTED);
      article.setWrite(WriteMode.DUAL_WRITE);
      fakeArticleService();
    } else {
      routeOff();
    }
  }

  private void routeOff() {
    DomainRoute article = properties.getArticle();
    article.setEnabled(false);
    article.setRead(ReadMode.MONOLITH);
    article.setWrite(WriteMode.MONOLITH);
  }
}
