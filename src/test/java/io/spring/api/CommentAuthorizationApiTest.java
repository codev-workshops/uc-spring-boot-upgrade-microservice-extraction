package io.spring.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.JacksonCustomizations;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.CommentQueryService;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.comment.Comment;
import io.spring.core.comment.CommentRepository;
import io.spring.core.user.User;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Positive and edge authorization cases of comment deletion that CommentsApiTest does not cover:
 * the article author may delete a comment written by somebody else, the comment author may delete
 * their own comment on somebody else's article, and anonymous / unknown-article requests.
 */
@WebMvcTest(CommentsApi.class)
@Import({WebSecurityConfig.class, JacksonCustomizations.class})
public class CommentAuthorizationApiTest extends TestWithCurrentUser {
  @Autowired private MockMvc mvc;

  @MockBean private ArticleRepository articleRepository;
  @MockBean private CommentRepository commentRepository;
  @MockBean private CommentQueryService commentQueryService;

  private User anotherUser;

  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    RestAssuredMockMvc.mockMvc(mvc);
    anotherUser = new User("other@test.com", "other", "123", "", "");
  }

  @Test
  public void should_allow_article_author_to_delete_others_comment() {
    Article myArticle = article(user);
    Comment othersComment = new Comment("comment", anotherUser.getId(), myArticle.getId());
    when(commentRepository.findById(eq(myArticle.getId()), eq(othersComment.getId())))
        .thenReturn(Optional.of(othersComment));

    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}/comments/{id}", myArticle.getSlug(), othersComment.getId())
        .then()
        .statusCode(204);

    verify(commentRepository).remove(eq(othersComment));
  }

  @Test
  public void should_allow_comment_author_to_delete_own_comment_on_others_article() {
    Article othersArticle = article(anotherUser);
    Comment myComment = new Comment("comment", user.getId(), othersArticle.getId());
    when(commentRepository.findById(eq(othersArticle.getId()), eq(myComment.getId())))
        .thenReturn(Optional.of(myComment));

    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}/comments/{id}", othersArticle.getSlug(), myComment.getId())
        .then()
        .statusCode(204);

    verify(commentRepository).remove(eq(myComment));
  }

  @Test
  public void should_get_401_when_anonymous_deletes_comment() {
    Article myArticle = article(user);
    Comment comment = new Comment("comment", user.getId(), myArticle.getId());

    given()
        .when()
        .delete("/articles/{slug}/comments/{id}", myArticle.getSlug(), comment.getId())
        .then()
        .statusCode(401);
  }

  @Test
  public void should_get_404_when_deleting_comment_of_unknown_article() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}/comments/{id}", "no-such-slug", "no-such-comment")
        .then()
        .statusCode(404);
  }

  @Test
  public void should_get_404_when_deleting_unknown_comment() {
    Article myArticle = article(user);

    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}/comments/{id}", myArticle.getSlug(), "no-such-comment")
        .then()
        .statusCode(404);
  }

  private Article article(User author) {
    Article article =
        new Article(
            "title-" + author.getUsername(), "desc", "body", Arrays.asList("java"), author.getId());
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));
    return article;
  }
}
