package io.spring.harness;

import static org.springframework.test.web.client.ExpectedCount.between;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.service.JwtService;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.user.DualWriteUserCommand;
import io.spring.infrastructure.extraction.user.UserLookupCache;
import io.spring.infrastructure.extraction.user.UserServiceClient;
import io.spring.infrastructure.repository.MyBatisUserRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Phase 5 parallel-run harness for the User seam. {@link RoutePath#MONOLITH} runs with the flag OFF
 * (legacy MyBatis lookups and BCrypt check); {@link RoutePath#EXTRACTED} runs with {@code
 * extraction.user.enabled=true, read=extracted, write=dual-write} against a {@link
 * MockRestServiceServer} fake of user-service that implements every endpoint of the canonical
 * internal API (phase-5-user.md section 2.1) on top of the rows the monolith holds — reads through
 * the very MyBatis repository, {@code credentials/verify} through the monolith's BCrypt encoder.
 * Both sides must produce the goldens under {@code golden/user}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:sqlite:build/user-extracted-parallel-run.db")
public class UserExtractedParallelRunTest {
  @Autowired private MockMvc mvc;
  @Autowired private ExtractionProperties properties;
  @Autowired private UserServiceClient client;
  @Autowired private MyBatisUserRepository localUsers;
  @Autowired private DualWriteUserCommand dualWrite;
  @Autowired private UserLookupCache cache;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbc;

  private final ObjectMapper json = new ObjectMapper();
  private final List<String> remoteCalls = new ArrayList<>();
  private ParallelRunHarness harness;
  private MockRestServiceServer userService;
  private User author;
  private User reader;
  private User stranger;
  private String readerToken;

  @BeforeEach
  public void setUp() {
    harness = new ParallelRunHarness(mvc, true);
    cleanTables();
    cache.clear();
    remoteCalls.clear();
    userService =
        MockRestServiceServer.bindTo(client.getRestTemplate()).ignoreExpectOrder(true).build();

    author = new User("author@test.com", "author", passwordEncoder.encode("123"), "bio", "img");
    reader = new User("reader@test.com", "reader", passwordEncoder.encode("123"), "", "");
    stranger = new User("stranger@test.com", "stranger", passwordEncoder.encode("123"), "", "");
    localUsers.save(author);
    localUsers.save(reader);
    localUsers.save(stranger);
    localUsers.saveRelation(new FollowRelation(reader.getId(), author.getId()));
    articleRepository.save(
        new Article(
            "java article",
            "desc",
            "body",
            Collections.singletonList("java"),
            author.getId(),
            new DateTime(2024, 1, 3, 0, 0, DateTimeZone.UTC)));
    articleRepository.save(
        new Article(
            "stranger article",
            "desc",
            "body",
            Collections.emptyList(),
            stranger.getId(),
            new DateTime(2024, 1, 2, 0, 0, DateTimeZone.UTC)));
    readerToken = jwtService.toToken(reader);
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
          "article_favorites", "comments", "article_tags", "tags", "articles", "follows", "users"
        }) {
      jdbc.update("delete from " + table);
    }
  }

  // ---------------------------------------------------------------- auth

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void register_should_match_the_golden_and_mirror_the_hash(RoutePath route)
      throws Exception {
    configure(route);

    MvcResult result =
        mvc.perform(
                post("/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"user\":{\"email\":\"new@test.com\",\"username\":\"newbie\","
                            + "\"password\":\"pw123\"}}"))
            .andExpect(status().isCreated())
            .andReturn();
    harness.assertMatchesGolden(
        "user/register", harness.normalize(result.getResponse().getContentAsString()));

    User saved = localUsers.findByEmail("new@test.com").get();
    Assertions.assertTrue(passwordEncoder.matches("pw123", saved.getPassword()));
    Assertions.assertEquals(saved.getId(), jwtService.getSubFromToken(token(result)).orElse(null));
    if (route == RoutePath.EXTRACTED) {
      Assertions.assertTrue(
          remoteCalls.contains("POST /internal/users noauth"), remoteCalls::toString);
      Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
    } else {
      Assertions.assertTrue(remoteCalls.isEmpty());
    }

    mvc.perform(
            post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"user\":{\"email\":\"new@test.com\",\"username\":\"other\","
                        + "\"password\":\"pw123\"}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors.email[0]").value("duplicated email"));
    mvc.perform(
            post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"user\":{\"email\":\"other@test.com\",\"username\":\"newbie\","
                        + "\"password\":\"pw123\"}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors.username[0]").value("duplicated username"));
    userService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void login_should_match_the_golden_and_verify_credentials_remotely(RoutePath route)
      throws Exception {
    configure(route);

    MvcResult ok =
        mvc.perform(
                post("/users/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"user\":{\"email\":\"author@test.com\",\"password\":\"123\"}}"))
            .andExpect(status().isOk())
            .andReturn();
    harness.assertMatchesGolden(
        "user/login", harness.normalize(ok.getResponse().getContentAsString()));
    Assertions.assertEquals(author.getId(), jwtService.getSubFromToken(token(ok)).orElse(null));

    mvc.perform(
            post("/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user\":{\"email\":\"author@test.com\",\"password\":\"nope\"}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.message").value("invalid email or password"));
    mvc.perform(
            post("/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user\":{\"email\":\"nobody@test.com\",\"password\":\"123\"}}"))
        .andExpect(status().isUnprocessableEntity());

    if (route == RoutePath.EXTRACTED) {
      Assertions.assertEquals(
          2,
          remoteCalls.stream()
              .filter(
                  c ->
                      c.equals(
                          "POST /internal/users/" + author.getId() + "/credentials/verify noauth"))
              .count(),
          remoteCalls::toString);
    } else {
      Assertions.assertTrue(remoteCalls.isEmpty());
    }
    userService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void graphql_login_uses_the_same_verification(RoutePath route) throws Exception {
    configure(route);
    Map<String, String> body = new HashMap<>();
    body.put(
        "query",
        "mutation { login(email: \"author@test.com\", password: \"123\") { user { username email profile { username bio image following } } } }");
    mvc.perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.login.user.username").value("author"))
        .andExpect(jsonPath("$.data.login.user.email").value("author@test.com"));
    body.put(
        "query",
        "mutation { login(email: \"author@test.com\", password: \"nope\") { user { username } } }");
    mvc.perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.errors[0].message")
                .value(Matchers.containsString("invalid email or password")));
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void current_user_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);
    String envelope =
        harness.captureEnvelope(
            route, get("/user").header("Authorization", "Token " + readerToken));
    harness.assertMatchesGolden("user/current-user", envelope);
    mvc.perform(get("/user")).andExpect(status().isUnauthorized());
    mvc.perform(get("/user").header("Authorization", "Token not-a-jwt"))
        .andExpect(status().isUnauthorized());
    userService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void update_user_should_match_the_golden_and_keep_empty_fields(RoutePath route)
      throws Exception {
    configure(route);
    String envelope =
        harness.captureEnvelope(
            route,
            put("/user")
                .header("Authorization", "Token " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user\":{\"bio\":\"new bio\",\"image\":\"\",\"email\":\"\"}}"));
    harness.assertMatchesGolden("user/update-user", envelope);

    User saved = localUsers.findById(reader.getId()).get();
    Assertions.assertEquals("new bio", saved.getBio());
    Assertions.assertEquals("reader@test.com", saved.getEmail());
    Assertions.assertTrue(passwordEncoder.matches("123", saved.getPassword()));
    if (route == RoutePath.EXTRACTED) {
      Assertions.assertTrue(
          remoteCalls.contains("PUT /internal/users/" + reader.getId() + " auth"),
          remoteCalls::toString);
    }

    mvc.perform(
            put("/user")
                .header("Authorization", "Token " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user\":{\"username\":\"author\"}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors.username[0]").value("username already exist"));
    userService.verify();
  }

  // ---------------------------------------------------------------- profiles

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void profile_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);
    String following =
        harness.captureEnvelope(
            route, get("/profiles/author").header("Authorization", "Token " + readerToken));
    String anonymous = harness.captureEnvelope(route, get("/profiles/author"));
    harness.assertMatchesGolden("user/profile-following", following);
    harness.assertMatchesGolden("user/profile-anonymous", anonymous);
    mvc.perform(get("/profiles/nobody")).andExpect(status().isNotFound());
    userService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void follow_and_unfollow_should_match_the_goldens(RoutePath route) throws Exception {
    configure(route);
    String followed =
        harness.captureEnvelope(
            route,
            post("/profiles/stranger/follow").header("Authorization", "Token " + readerToken));
    harness.assertMatchesGolden("user/follow", followed);
    Assertions.assertTrue(localUsers.findRelation(reader.getId(), stranger.getId()).isPresent());

    String unfollowed =
        harness.captureEnvelope(
            route,
            delete("/profiles/stranger/follow").header("Authorization", "Token " + readerToken));
    harness.assertMatchesGolden("user/unfollow", unfollowed);
    Assertions.assertFalse(localUsers.findRelation(reader.getId(), stranger.getId()).isPresent());

    mvc.perform(delete("/profiles/stranger/follow").header("Authorization", "Token " + readerToken))
        .andExpect(status().isNotFound());
    mvc.perform(post("/profiles/nobody/follow").header("Authorization", "Token " + readerToken))
        .andExpect(status().isNotFound());
    mvc.perform(post("/profiles/stranger/follow")).andExpect(status().isUnauthorized());

    if (route == RoutePath.EXTRACTED) {
      String follows = "/internal/users/" + reader.getId() + "/follows/" + stranger.getId();
      Assertions.assertTrue(
          remoteCalls.contains("PUT " + follows + " auth"), remoteCalls::toString);
      Assertions.assertTrue(
          remoteCalls.contains("DELETE " + follows + " auth"), remoteCalls::toString);
      Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
    } else {
      Assertions.assertTrue(remoteCalls.isEmpty());
    }
    userService.verify();
  }

  // ---------------------------------------------------------------- composition

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void authenticated_article_should_match_the_golden(RoutePath route) throws Exception {
    configure(route);
    String envelope =
        harness.captureEnvelope(
            route, get("/articles/java-article").header("Authorization", "Token " + readerToken));
    harness.assertMatchesGolden("user/article-by-slug-following-author", envelope);
    userService.verify();
  }

  @ParameterizedTest
  @EnumSource(RoutePath.class)
  public void article_list_and_feed_should_match_the_goldens(RoutePath route) throws Exception {
    configure(route);
    String list =
        harness.captureEnvelope(
            route, get("/articles").header("Authorization", "Token " + readerToken));
    String feed =
        harness.captureEnvelope(
            route, get("/articles/feed").header("Authorization", "Token " + readerToken));
    String byAuthor = harness.captureEnvelope(route, get("/articles?author=author"));
    harness.assertMatchesGolden("user/articles-list-author-following", list);
    harness.assertMatchesGolden("user/feed", feed);
    harness.assertMatchesGolden("user/articles-by-author-anonymous", byAuthor);
    userService.verify();
  }

  // ---------------------------------------------------------------- fake user-service

  private void configure(RoutePath route) {
    DomainRoute user = properties.getUser();
    if (route == RoutePath.EXTRACTED) {
      user.setEnabled(true);
      user.setRead(ReadMode.EXTRACTED);
      user.setWrite(WriteMode.DUAL_WRITE);
      fakeUserService();
    } else {
      routeOff();
    }
  }

  private void routeOff() {
    DomainRoute user = properties.getUser();
    user.setEnabled(false);
    user.setRead(ReadMode.MONOLITH);
    user.setWrite(WriteMode.MONOLITH);
  }

  private void fakeUserService() {
    userService
        .expect(
            between(0, Integer.MAX_VALUE),
            requestTo(Matchers.startsWith(userUrl("/internal/users"))))
        .andRespond(
            request -> {
              URI uri = request.getURI();
              String path = uri.getPath();
              HttpMethod method = request.getMethod();
              boolean auth = request.getHeaders().containsKey("Authorization");
              remoteCalls.add(method + " " + path + (auth ? " auth" : " noauth"));
              if (method == HttpMethod.GET && !auth) {
                return read(path, UriComponentsBuilder.fromUri(uri).build().getQueryParams());
              }
              Assertions.assertFalse(
                  method == HttpMethod.GET, "reads must not carry credentials: " + path);
              return write(method, path, ((MockClientHttpRequest) request).getBodyAsString());
            });
  }

  private MockClientHttpResponse read(String path, MultiValueMap<String, String> params)
      throws java.io.IOException {
    String[] parts = path.substring("/internal/users".length()).split("/");
    // parts[0] is "" because of the leading slash
    if (parts.length == 1) {
      List<Map<String, Object>> users = new ArrayList<>();
      for (String id : Arrays.asList(decode(params.getFirst("ids")).split(","))) {
        localUsers.findById(id).ifPresent(u -> users.add(row(u)));
      }
      return ok(Collections.singletonMap("users", users));
    }
    if (parts.length == 3 && parts[1].equals("by-username")) {
      return single(localUsers.findByUsername(decode(parts[2])));
    }
    if (parts.length == 3 && parts[1].equals("by-email")) {
      return single(localUsers.findByEmail(decode(parts[2])));
    }
    if (parts.length == 2) {
      return single(localUsers.findById(parts[1]));
    }
    String userId = parts[1];
    if (parts.length == 3 && parts[2].equals("following")) {
      List<String> ids = new ArrayList<>();
      for (String id : decode(params.getFirst("ids")).split(",")) {
        if (localUsers.findRelation(userId, id).isPresent()) {
          ids.add(id);
        }
      }
      return ok(Collections.singletonMap("followingIds", ids));
    }
    if (parts.length == 3 && parts[2].equals("followed")) {
      return ok(
          Collections.singletonMap(
              "followedIds",
              jdbc.queryForList(
                  "select follow_id from follows where user_id = ?", String.class, userId)));
    }
    if (parts.length == 4 && parts[2].equals("follows")) {
      return ok(
          Collections.singletonMap(
              "following", localUsers.findRelation(userId, parts[3]).isPresent()));
    }
    throw new AssertionError("unexpected read " + path);
  }

  private MockClientHttpResponse write(HttpMethod method, String path, String body)
      throws java.io.IOException {
    String[] parts = path.substring("/internal/users".length()).split("/");
    Map<?, ?> payload = body.isEmpty() ? Collections.emptyMap() : json.readValue(body, Map.class);
    if (parts.length == 1 && method == HttpMethod.POST) {
      Assertions.assertFalse(payload.containsKey("password"), "raw password must never be sent");
      Assertions.assertTrue(payload.get("passwordHash").toString().startsWith("$2a$"));
      Optional<User> user = localUsers.findById(payload.get("id").toString());
      Assertions.assertTrue(user.isPresent(), "dual-write must write locally first");
      MockClientHttpResponse response =
          new MockClientHttpResponse(
              json.writeValueAsBytes(Collections.singletonMap("user", row(user.get()))),
              HttpStatus.CREATED);
      response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
      return response;
    }
    if (parts.length == 2 && method == HttpMethod.PUT) {
      Assertions.assertFalse(payload.containsKey("password"), "raw password must never be sent");
      Assertions.assertFalse(payload.containsKey("id"));
      return single(localUsers.findById(parts[1]));
    }
    if (parts.length == 4 && parts[2].equals("credentials") && method == HttpMethod.POST) {
      Optional<User> user = localUsers.findById(parts[1]);
      if (!user.isPresent()) {
        return new MockClientHttpResponse(new byte[0], HttpStatus.NOT_FOUND);
      }
      boolean valid =
          passwordEncoder.matches(payload.get("password").toString(), user.get().getPassword());
      return ok(Collections.singletonMap("valid", valid));
    }
    if (parts.length == 4 && parts[2].equals("follows")) {
      Assertions.assertTrue(method == HttpMethod.PUT || method == HttpMethod.DELETE);
      return new MockClientHttpResponse(new byte[0], HttpStatus.NO_CONTENT);
    }
    throw new AssertionError("unexpected write " + method + " " + path);
  }

  private MockClientHttpResponse single(Optional<User> user) throws java.io.IOException {
    if (!user.isPresent()) {
      return new MockClientHttpResponse(new byte[0], HttpStatus.NOT_FOUND);
    }
    return ok(Collections.singletonMap("user", row(user.get())));
  }

  private MockClientHttpResponse ok(Object body) throws java.io.IOException {
    MockClientHttpResponse response =
        new MockClientHttpResponse(json.writeValueAsBytes(body), HttpStatus.OK);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return response;
  }

  private static Map<String, Object> row(User user) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", user.getId());
    map.put("username", user.getUsername());
    map.put("email", user.getEmail());
    map.put("bio", user.getBio());
    map.put("image", user.getImage());
    return map;
  }

  private String token(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString()).at("/user/token").asText();
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private String userUrl(String path) {
    return properties.getUser().getBaseUrl().toString() + path;
  }
}
