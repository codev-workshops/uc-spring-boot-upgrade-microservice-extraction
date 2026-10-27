package io.spring.articles.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.spring.articles.infrastructure.client.UserServiceClient;
import io.spring.articles.infrastructure.client.UserServiceException;
import io.spring.articles.infrastructure.client.dto.FavoriteDto;
import io.spring.articles.infrastructure.client.dto.ProfileDto;
import io.spring.articles.infrastructure.client.dto.UserDto;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ArticlesApiTest {
  private static final String JOHN = "john-token";
  private static final String JANE = "jane-token";

  @Autowired private MockMvc mvc;

  @MockBean private UserServiceClient userServiceClient;

  @BeforeEach
  void setUp() {
    when(userServiceClient.findCurrentUser(JOHN))
        .thenReturn(Optional.of(new UserDto("user-1", "johndoe")));
    when(userServiceClient.findCurrentUser(JANE))
        .thenReturn(Optional.of(new UserDto("user-2", "janedoe")));
    when(userServiceClient.findProfilesByIds(anyCollection(), any()))
        .thenAnswer(
            inv -> {
              Collection<String> ids = inv.getArgument(0);
              return ids.stream()
                  .map(id -> new ProfileDto(id, "name-" + id, "bio", "img", false))
                  .toList();
            });
    when(userServiceClient.findFavorites(anyCollection(), any()))
        .thenAnswer(
            inv -> {
              Collection<String> ids = inv.getArgument(0);
              return ids.stream().map(id -> new FavoriteDto(id, 2, true)).toList();
            });
  }

  private ResultActions create(String token, String title, String... tags) throws Exception {
    String tagList = tags.length == 0 ? "" : "\"" + String.join("\",\"", tags) + "\"";
    return mvc.perform(
        post("/articles")
            .header("Authorization", "Token " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"article\":{\"title\":\""
                    + title
                    + "\",\"description\":\"desc\",\"body\":\"body\",\"tagList\":["
                    + tagList
                    + "]}}"));
  }

  private String createdId(String token, String title, String... tags) throws Exception {
    String json = create(token, title, tags).andReturn().getResponse().getContentAsString();
    return JsonPath.read(json, "$.article.id");
  }

  @Test
  void should_create_article_with_author_and_favorites_from_user_service() throws Exception {
    create(JOHN, "Hello World", "java", "spring")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.article.slug").value("hello-world"))
        .andExpect(jsonPath("$.article.tagList", containsInAnyOrder("java", "spring")))
        .andExpect(jsonPath("$.article.author.username").value("name-user-1"))
        .andExpect(jsonPath("$.article.author.following").value(false))
        .andExpect(jsonPath("$.article.favoritesCount").value(2))
        .andExpect(jsonPath("$.article.favorited").value(true))
        .andExpect(jsonPath("$.article.authorId").doesNotExist());
  }

  @Test
  void should_require_authentication_to_create() throws Exception {
    mvc.perform(
            post("/articles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"article\":{\"title\":\"t\",\"description\":\"d\",\"body\":\"b\"}}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_reject_invalid_and_duplicated_articles_with_422() throws Exception {
    create(JOHN, "")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors.title").exists());
    create(JOHN, "Same Title").andExpect(status().isOk());
    create(JANE, "Same Title")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors.title[0]").value("article name exists"));
  }

  @Test
  void should_get_update_and_delete_article_with_ownership_checks() throws Exception {
    create(JOHN, "Owned Article").andExpect(status().isOk());
    mvc.perform(get("/articles/owned-article"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.article.title").value("Owned Article"));
    mvc.perform(get("/articles/missing")).andExpect(status().isNotFound());

    String update = "{\"article\":{\"title\":\"Renamed Article\"}}";
    mvc.perform(
            put("/articles/owned-article")
                .header("Authorization", "Token " + JANE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(update))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/articles/owned-article")
                .header("Authorization", "Token " + JOHN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(update))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.article.slug").value("renamed-article"));

    mvc.perform(delete("/articles/renamed-article").header("Authorization", "Token " + JANE))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/articles/renamed-article").header("Authorization", "Token " + JOHN))
        .andExpect(status().isNoContent());
    mvc.perform(get("/articles/renamed-article")).andExpect(status().isNotFound());
  }

  @Test
  void should_filter_articles_by_tag_author_and_favorited() throws Exception {
    String johns = createdId(JOHN, "Java Tips", "java");
    String janes = createdId(JANE, "Go Tips", "go");
    when(userServiceClient.findProfileByUsername(eq("janedoe"), any()))
        .thenReturn(Optional.of(new ProfileDto("user-2", "janedoe", null, null, false)));
    when(userServiceClient.findArticleIdsFavoritedBy("johndoe")).thenReturn(List.of(janes));

    mvc.perform(get("/articles").param("tag", "java"))
        .andExpect(jsonPath("$.articlesCount").value(1))
        .andExpect(jsonPath("$.articles[0].id").value(johns));
    mvc.perform(get("/articles").param("author", "janedoe"))
        .andExpect(jsonPath("$.articlesCount").value(1))
        .andExpect(jsonPath("$.articles[0].id").value(janes));
    mvc.perform(get("/articles").param("author", "nobody"))
        .andExpect(jsonPath("$.articlesCount").value(0));
    mvc.perform(get("/articles").param("favorited", "johndoe"))
        .andExpect(jsonPath("$.articlesCount").value(1))
        .andExpect(jsonPath("$.articles[0].id").value(janes));
    mvc.perform(get("/articles").param("limit", "1"))
        .andExpect(jsonPath("$.articlesCount").value(2))
        .andExpect(jsonPath("$.articles.length()").value(1));
  }

  @Test
  void should_return_feed_of_followed_authors() throws Exception {
    createdId(JOHN, "Johns Post");
    String janes = createdId(JANE, "Janes Post");
    when(userServiceClient.findFollowedUserIds(JOHN)).thenReturn(List.of("user-2"));

    mvc.perform(get("/articles/feed").header("Authorization", "Token " + JOHN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.articlesCount").value(1))
        .andExpect(jsonPath("$.articles[0].id").value(janes));
    mvc.perform(get("/articles/feed")).andExpect(status().isUnauthorized());
  }

  @Test
  void should_list_tags() throws Exception {
    create(JOHN, "Tagged", "alpha", "beta").andExpect(status().isOk());
    mvc.perform(get("/tags"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tags", hasItems("alpha", "beta")));
  }

  @Test
  void should_return_503_when_user_service_cannot_authenticate() throws Exception {
    when(userServiceClient.findCurrentUser("down"))
        .thenThrow(
            new UserServiceException("User service unavailable: could not authenticate", null));
    mvc.perform(get("/articles/feed").header("Authorization", "Token down"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            jsonPath(
                "$.errors.userService",
                contains("User service unavailable: could not authenticate")));
  }

  @Test
  void should_return_503_when_author_profiles_are_unavailable() throws Exception {
    create(JOHN, "Needs Profiles").andExpect(status().isOk());
    when(userServiceClient.findProfilesByIds(anyCollection(), any()))
        .thenThrow(new UserServiceException("User service unavailable: profiles", null));
    mvc.perform(get("/articles/needs-profiles"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.errors.userService[0]").value("User service unavailable: profiles"));
  }
}
