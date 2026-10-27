package io.spring.articles.infrastructure.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import io.spring.articles.infrastructure.client.dto.FavoriteDto;
import io.spring.articles.infrastructure.client.dto.ProfileDto;
import io.spring.articles.infrastructure.client.dto.UserDto;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class UserServiceClientTest {
  private static final String BASE = "http://user-service";

  private MockRestServiceServer server;
  private UserServiceClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client = new UserServiceClient(builder, BASE);
  }

  @Test
  void should_resolve_current_user_and_forward_token() {
    server
        .expect(requestTo(BASE + "/internal/me"))
        .andExpect(header("Authorization", "Token abc"))
        .andRespond(
            withSuccess(
                "{\"user\":{\"id\":\"u1\",\"username\":\"john\",\"extra\":1}}",
                MediaType.APPLICATION_JSON));
    assertEquals(Optional.of(new UserDto("u1", "john")), client.findCurrentUser("abc"));
    server.verify();
  }

  @Test
  void should_return_empty_for_rejected_token() {
    server.expect(requestTo(BASE + "/internal/me")).andRespond(withUnauthorizedRequest());
    assertTrue(client.findCurrentUser("bad").isEmpty());
  }

  @Test
  void should_throw_domain_exception_when_user_service_fails() {
    server.expect(requestTo(BASE + "/internal/me")).andRespond(withServerError());
    UserServiceException e =
        assertThrows(UserServiceException.class, () -> client.findCurrentUser("abc"));
    assertTrue(e.getMessage().startsWith("User service unavailable"));
  }

  @Test
  void should_throw_domain_exception_when_user_service_is_unreachable() {
    server
        .expect(requestTo(BASE + "/internal/me/following"))
        .andRespond(withException(new IOException("Connection refused")));
    assertThrows(UserServiceException.class, () -> client.findFollowedUserIds("abc"));
  }

  @Test
  void should_load_profiles_by_ids_anonymously() {
    server
        .expect(requestTo(BASE + "/internal/profiles?ids=a&ids=b"))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(
            withSuccess(
                "{\"profiles\":[{\"id\":\"a\",\"username\":\"alice\",\"bio\":null,"
                    + "\"image\":\"img\",\"following\":false}]}",
                MediaType.APPLICATION_JSON));
    assertEquals(
        List.of(new ProfileDto("a", "alice", null, "img", false)),
        client.findProfilesByIds(List.of("a", "b"), null));
    server.verify();
  }

  @Test
  void should_not_call_user_service_for_empty_ids() {
    assertTrue(client.findProfilesByIds(List.of(), "abc").isEmpty());
    assertTrue(client.findFavorites(List.of(), "abc").isEmpty());
    server.verify();
  }

  @Test
  void should_return_profile_by_username() {
    server
        .expect(requestTo(BASE + "/internal/profiles?username=jane"))
        .andRespond(
            withSuccess(
                "{\"profiles\":[{\"id\":\"u2\",\"username\":\"jane\",\"following\":true}]}",
                MediaType.APPLICATION_JSON));
    assertEquals("u2", client.findProfileByUsername("jane", "abc").get().id());
  }

  @Test
  void should_load_favorites() {
    server
        .expect(requestTo(BASE + "/internal/favorites?articleIds=a1"))
        .andRespond(
            withSuccess(
                "{\"favorites\":[{\"articleId\":\"a1\",\"favoritesCount\":3,\"favorited\":true}]}",
                MediaType.APPLICATION_JSON));
    assertEquals(List.of(new FavoriteDto("a1", 3, true)), client.findFavorites(List.of("a1"), "t"));
  }

  @Test
  void should_default_favorites_when_user_service_fails() {
    server
        .expect(requestTo(BASE + "/internal/favorites?articleIds=a1"))
        .andRespond(withServerError());
    assertTrue(client.findFavorites(List.of("a1"), null).isEmpty());
  }

  @Test
  void should_load_article_ids_favorited_by_user() {
    server
        .expect(requestTo(BASE + "/internal/favorites/by-user/jane"))
        .andRespond(withSuccess("{\"articleIds\":[\"a1\"]}", MediaType.APPLICATION_JSON));
    assertEquals(List.of("a1"), client.findArticleIdsFavoritedBy("jane"));
  }
}
