package io.spring.harness;

import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.comment.Comment;
import io.spring.core.service.JwtService;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.comment.CommentServiceClient;
import io.spring.infrastructure.extraction.comment.DualWriteCommentCommand;
import io.spring.infrastructure.repository.MyBatisCommentRepository;
import java.util.Collections;
import java.util.HashMap;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 2 parallel-run harness: the three REST comment endpoints and the GraphQL comments
 * connection run through the real application against an in-memory database. {@link
 * RoutePath#MONOLITH} runs with the flag OFF; {@link RoutePath#EXTRACTED} runs with {@code
 * extraction.comment.enabled=true, read=extracted, write=dual-write} against a {@link
 * MockRestServiceServer} stub of comment-service answering the canonical internal API
 * (phase-2-comment.md section 2.1) with the same rows the monolith holds. Both sides must produce
 * the goldens under {@code golden/comment}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:sqlite:build/comment-extracted-parallel-run.db")
public class CommentExtractedParallelRunTest {
  @Autowired private MockMvc mvc;
  @Autowired private ExtractionProperties properties;
  @Autowired private CommentServiceClient client;
  @Autowired private DualWriteCommentCommand dualWrite;
  @Autowired private UserRepository userRepository;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private MyBatisCommentRepository monolithComments;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbc;

  private final ObjectMapper json = new ObjectMapper();
  private ParallelRunHarness harness;
  private MockRestServiceServer commentService;
  private User author;
  private User reader;
  private Article article;
  private Comment older;
  private Comment newer;
  private String readerToken;
  private String authorToken;

  @BeforeEach
  public void setUp() {
    harness = new ParallelRunHarness(mvc, true);
    cleanTables();
    commentService =
        MockRestServiceServer.bindTo(client.getRestTemplate()).ignoreExpectOrder(true).build();

    author = new User("author@test.com", "author", "123", "bio", "img");
    reader = new User("reader@test.com", "reader", "123", "", "");
    userRepository.save(author);
    userRepository.save(reader);
    article =
        new Article(
            "parallel run", "desc", "body", Collections.singletonList("java"), author.getId());
    articleRepository.save(article);
    older =
        new Comment(
            "11111111-1111-1111-1111-111111111111",
            "older",
            reader.getId(),
            article.getId(),
            new DateTime(2024, 1, 1, 0, 0, DateTimeZone.UTC));
    newer =
        new Comment(
            "22222222-2222-2222-2222-222222222222",
            "newer",
            author.getId(),
            article.getId(),
            new DateTime(2024, 1, 2, 0, 0, DateTimeZone.UTC));
    monolithComments.save(older);
    monolithComments.save(newer);
    readerToken = jwtService.toToken(reader);
    authorToken = jwtService.toToken(author);
    dualWrite.clearPending();
  }

  @AfterEach
  public void tearDown() {
    routeOff();
    cleanTables();
  }

  private void cleanTables() {
    for (String table :
        new String[] {"comments", "article_tags", "tags", "articles", "follows", "users"}) {
      jdbc.update("delete from " + table);
    }
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void list_envelope_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      stubList(newer, older);
    }

    String envelope =
        harness.captureEnvelope(
            route,
            get("/articles/{slug}/comments", article.getSlug())
                .header("Authorization", "Token " + readerToken));

    harness.assertMatchesGolden("comment/comments-list", envelope);
    commentService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void empty_list_envelope_should_match_the_golden(RoutePath route) throws Exception {
    jdbc.update("delete from comments");
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      stubList();
    }

    String envelope =
        harness.captureEnvelope(route, get("/articles/{slug}/comments", article.getSlug()));

    harness.assertMatchesGolden("comment/comments-empty", envelope);
    commentService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void create_envelope_should_match_the_golden_and_dual_write(RoutePath route)
      throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      commentService
          .expect(
              once(), requestTo(commentUrl("/internal/articles/" + article.getId() + "/comments")))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("Authorization", "Token " + readerToken))
          .andExpect(jsonPath("$.body").value("hello"))
          .andExpect(jsonPath("$.userId").value(reader.getId()))
          .andExpect(jsonPath("$.id").exists())
          .andExpect(jsonPath("$.createdAt").exists())
          .andRespond(
              withStatus(HttpStatus.CREATED)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(
                      row(
                          "33333333-3333-3333-3333-333333333333",
                          "hello",
                          reader.getId(),
                          "2024-01-03T00:00:00.000Z",
                          "comment")));
    }

    MvcResult result =
        mvc.perform(
                post("/articles/{slug}/comments", article.getSlug())
                    .header("Authorization", "Token " + readerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":{\"body\":\"hello\"}}"))
            .andExpect(status().isCreated())
            .andReturn();

    harness.assertMatchesGolden(
        "comment/comment-create", harness.normalize(result.getResponse().getContentAsString()));
    commentService.verify();
    Assertions.assertEquals(3, jdbc.queryForObject("select count(*) from comments", Integer.class));
    Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void delete_by_comment_author_returns_204_and_dual_writes(RoutePath route)
      throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      expectRemoteDelete(older, readerToken);
    }

    mvc.perform(
            delete("/articles/{slug}/comments/{id}", article.getSlug(), older.getId())
                .header("Authorization", "Token " + readerToken))
        .andExpect(status().isNoContent());

    commentService.verify();
    Assertions.assertFalse(monolithComments.findById(article.getId(), older.getId()).isPresent());
    Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void delete_by_article_author_returns_204(RoutePath route) throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      expectRemoteDelete(older, authorToken);
    }

    mvc.perform(
            delete("/articles/{slug}/comments/{id}", article.getSlug(), older.getId())
                .header("Authorization", "Token " + authorToken))
        .andExpect(status().isNoContent());

    commentService.verify();
    Assertions.assertFalse(monolithComments.findById(article.getId(), older.getId()).isPresent());
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void stranger_delete_is_403_before_any_remote_call(RoutePath route) throws Exception {
    User stranger = new User("stranger@test.com", "stranger", "123", "", "");
    userRepository.save(stranger);
    configure(route);

    mvc.perform(
            delete("/articles/{slug}/comments/{id}", article.getSlug(), older.getId())
                .header("Authorization", "Token " + jwtService.toToken(stranger)))
        .andExpect(status().isForbidden());

    commentService.verify();
    Assertions.assertTrue(monolithComments.findById(article.getId(), older.getId()).isPresent());
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void unknown_comment_is_404_and_unknown_slug_is_404_before_any_remote_call(RoutePath route)
      throws Exception {
    configure(route);

    mvc.perform(
            delete("/articles/{slug}/comments/{id}", article.getSlug(), "missing")
                .header("Authorization", "Token " + readerToken))
        .andExpect(status().isNotFound());
    mvc.perform(
            delete("/articles/{slug}/comments/{id}", "no-such-article", older.getId())
                .header("Authorization", "Token " + readerToken))
        .andExpect(status().isNotFound());
    mvc.perform(get("/articles/{slug}/comments", "no-such-article"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/articles/{slug}/comments", "no-such-article")
                .header("Authorization", "Token " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":{\"body\":\"hello\"}}"))
        .andExpect(status().isNotFound());

    commentService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void blank_body_is_422_before_any_remote_call(RoutePath route) throws Exception {
    configure(route);

    MvcResult result =
        mvc.perform(
                post("/articles/{slug}/comments", article.getSlug())
                    .header("Authorization", "Token " + readerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":{\"body\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();

    harness.assertMatchesGolden(
        "comment/comment-blank-body", harness.normalize(result.getResponse().getContentAsString()));
    commentService.verify();
    Assertions.assertEquals(2, jdbc.queryForObject("select count(*) from comments", Integer.class));
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void graphql_comments_connection_should_match_the_golden(RoutePath route)
      throws Exception {
    configure(route);
    if (route == RoutePath.EXTRACTED) {
      stubCursor("limit=1&direction=next", newer, older);
    }

    String envelope = harness.normalize(graphql(commentsQuery(article.getSlug(), "first: 1")));

    harness.assertMatchesGolden("comment/graphql-comments-first-page", envelope);
    commentService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void graphql_cursor_boundaries_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);
    long newerMillis = newer.getCreatedAt().getMillis();
    long olderMillis = older.getCreatedAt().getMillis();
    if (route == RoutePath.EXTRACTED) {
      stubCursor("limit=5&direction=next&cursor=" + newerMillis, older);
      stubCursor("limit=5&direction=prev&cursor=" + olderMillis, newer);
      stubCursor("limit=5&direction=next&cursor=" + olderMillis);
    }

    String afterNewer =
        harness.normalize(
            graphql(commentsQuery(article.getSlug(), "first: 5, after: \"" + newerMillis + "\"")));
    String beforeOlder =
        harness.normalize(
            graphql(commentsQuery(article.getSlug(), "last: 5, before: \"" + olderMillis + "\"")));
    String pastTheEnd =
        harness.normalize(
            graphql(commentsQuery(article.getSlug(), "first: 5, after: \"" + olderMillis + "\"")));

    harness.assertMatchesGolden("comment/graphql-comments-after-newer", afterNewer);
    harness.assertMatchesGolden("comment/graphql-comments-before-older", beforeOlder);
    harness.assertMatchesGolden("comment/graphql-comments-past-the-end", pastTheEnd);
    commentService.verify();
  }

  @Test
  public void monolith_and_extracted_envelopes_are_identical() throws Exception {
    routeOff();
    String monolithList =
        harness.captureEnvelope(
            RoutePath.MONOLITH,
            get("/articles/{slug}/comments", article.getSlug())
                .header("Authorization", "Token " + readerToken));
    String monolithGraphql =
        harness.normalize(graphql(commentsQuery(article.getSlug(), "first: 10")));

    configure(RoutePath.EXTRACTED);
    stubList(newer, older);
    stubCursor("limit=10&direction=next", newer, older);
    String extractedList =
        harness.captureEnvelope(
            RoutePath.EXTRACTED,
            get("/articles/{slug}/comments", article.getSlug())
                .header("Authorization", "Token " + readerToken));
    String extractedGraphql =
        harness.normalize(graphql(commentsQuery(article.getSlug(), "first: 10")));

    harness.assertEnvelopesMatch(monolithList, extractedList);
    harness.assertEnvelopesMatch(monolithGraphql, extractedGraphql);
    Assertions.assertTrue(extractedList.contains("\"following\" : false"));
  }

  @Test
  public void mirror_failure_does_not_surface_and_is_recorded() throws Exception {
    configure(RoutePath.EXTRACTED);
    commentService
        .expect(
            once(), requestTo(commentUrl("/internal/articles/" + article.getId() + "/comments")))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withServerError());

    MvcResult result =
        mvc.perform(
                post("/articles/{slug}/comments", article.getSlug())
                    .header("Authorization", "Token " + readerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":{\"body\":\"hello\"}}"))
            .andExpect(status().isCreated())
            .andReturn();

    harness.assertMatchesGolden(
        "comment/comment-create", harness.normalize(result.getResponse().getContentAsString()));
    Assertions.assertEquals(1, dualWrite.pendingMirrorOperations().size());
    Assertions.assertEquals(3, jdbc.queryForObject("select count(*) from comments", Integer.class));
  }

  @Test
  public void extracted_read_falls_back_to_the_monolith_when_the_service_is_down()
      throws Exception {
    configure(RoutePath.EXTRACTED);
    commentService
        .expect(
            manyTimes(),
            requestTo(commentUrl("/internal/articles/" + article.getId() + "/comments")))
        .andRespond(withServerError());

    String envelope =
        harness.captureEnvelope(
            RoutePath.EXTRACTED,
            get("/articles/{slug}/comments", article.getSlug())
                .header("Authorization", "Token " + readerToken));

    harness.assertMatchesGolden("comment/comments-list", envelope);
  }

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

  private static String commentsQuery(String slug, String args) {
    return "{ article(slug: \""
        + slug
        + "\") { comments("
        + args
        + ") { pageInfo { hasNextPage hasPreviousPage startCursor endCursor }"
        + " edges { cursor node { id body createdAt updatedAt author { username bio image following } } } } } }";
  }

  private void stubList(Comment... rows) {
    commentService
        .expect(
            manyTimes(),
            requestTo(commentUrl("/internal/articles/" + article.getId() + "/comments")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(rows(rows), MediaType.APPLICATION_JSON));
  }

  private void stubCursor(String query, Comment... rows) {
    commentService
        .expect(
            manyTimes(),
            requestTo(
                commentUrl("/internal/articles/" + article.getId() + "/comments/cursor?" + query)))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(rows(rows), MediaType.APPLICATION_JSON));
  }

  private void expectRemoteDelete(Comment comment, String token) {
    commentService
        .expect(
            once(),
            requestTo(
                commentUrl(
                    "/internal/articles/" + article.getId() + "/comments/" + comment.getId())))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("Authorization", "Token " + token))
        .andRespond(withNoContent());
  }

  private String rows(Comment... rows) {
    StringBuilder sb = new StringBuilder("{\"comments\":[");
    for (int i = 0; i < rows.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      Comment c = rows[i];
      sb.append(row(c.getId(), c.getBody(), c.getUserId(), iso(c.getCreatedAt()), null));
    }
    return sb.append("]}").toString();
  }

  private String row(String id, String body, String userId, String createdAt, String envelope) {
    String json =
        "{\"id\":\""
            + id
            + "\",\"body\":\""
            + body
            + "\",\"articleId\":\""
            + article.getId()
            + "\",\"userId\":\""
            + userId
            + "\",\"createdAt\":\""
            + createdAt
            + "\",\"updatedAt\":\""
            + createdAt
            + "\"}";
    return envelope == null ? json : "{\"" + envelope + "\":" + json + "}";
  }

  private static String iso(DateTime value) {
    return value.withZone(DateTimeZone.UTC).toString();
  }

  private String commentUrl(String path) {
    return properties.getComment().getBaseUrl() + path;
  }

  private void configure(RoutePath route) {
    DomainRoute comment = properties.getComment();
    if (route == RoutePath.EXTRACTED) {
      comment.setEnabled(true);
      comment.setRead(ReadMode.EXTRACTED);
      comment.setWrite(WriteMode.DUAL_WRITE);
    } else {
      routeOff();
    }
  }

  private void routeOff() {
    DomainRoute comment = properties.getComment();
    comment.setEnabled(false);
    comment.setRead(ReadMode.MONOLITH);
    comment.setWrite(WriteMode.MONOLITH);
  }
}
