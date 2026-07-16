package io.spring.api.internal;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.data.UserData;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.service.JwtService;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({InternalProfileApi.class, InternalArticleApi.class})
@Import(WebSecurityConfig.class)
@TestPropertySource(
    properties = {
      "jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret-test-secret-123456789",
      "internal.service-key=test-internal-service-key"
    })
public class InternalApiTest {
  private static final String INTERNAL_KEY = "test-internal-service-key";

  @MockBean private UserReadService userReadService;
  @MockBean private UserRelationshipQueryService userRelationshipQueryService;
  @MockBean private ArticleRepository articleRepository;
  @MockBean private UserRepository userRepository;
  @MockBean private JwtService jwtService;

  @Autowired private MockMvc mvc;

  @BeforeEach
  public void setUp() {
    RestAssuredMockMvc.mockMvc(mvc);
  }

  @Test
  public void shouldReturnViewerScopedBatchProfiles() {
    UserData user1 = new UserData("user-1", "one@example.com", "one", "bio one", "image one");
    UserData user2 = new UserData("user-2", "two@example.com", "two", "bio two", "image two");
    when(userReadService.findByIds(Arrays.asList("user-1", "user-2")))
        .thenReturn(Arrays.asList(user1, user2));
    when(userRelationshipQueryService.followingAuthors(
            "viewer-1", Arrays.asList("user-1", "user-2")))
        .thenReturn(Collections.singleton("user-2"));

    Map<String, Object> body = new HashMap<>();
    body.put("viewerId", "viewer-1");
    body.put("userIds", Arrays.asList("user-1", "user-2", "user-1"));

    given()
        .contentType("application/json")
        .header("X-Internal-Service-Key", INTERNAL_KEY)
        .body(body)
        .when()
        .post("/internal/profiles/batch")
        .then()
        .statusCode(200)
        .body("[0].id", equalTo("user-1"))
        .body("[0].following", equalTo(false))
        .body("[1].id", equalTo("user-2"))
        .body("[1].following", equalTo(true));
  }

  @Test
  public void shouldReturnArticleIdAndAuthorId() {
    Article article =
        new Article("Article title", "description", "body", Collections.emptyList(), "user-1");
    when(articleRepository.findBySlug(article.getSlug())).thenReturn(Optional.of(article));

    given()
        .header("X-Internal-Service-Key", INTERNAL_KEY)
        .when()
        .get("/internal/articles/{slug}", article.getSlug())
        .then()
        .statusCode(200)
        .body("id", equalTo(article.getId()))
        .body("authorId", equalTo("user-1"));
  }

  @Test
  public void shouldRejectMissingInternalServiceKey() {
    given().when().post("/internal/profiles/batch").then().statusCode(401);
  }

  @Test
  public void shouldRejectIncorrectInternalServiceKey() {
    given()
        .header("X-Internal-Service-Key", "test-internal-service-kez")
        .when()
        .post("/internal/profiles/batch")
        .then()
        .statusCode(401);
  }
}
