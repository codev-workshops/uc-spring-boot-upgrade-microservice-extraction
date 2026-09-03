package io.spring.infrastructure.extraction.user;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.spring.application.user.dto.UserRowDto;
import io.spring.core.user.User;
import io.spring.infrastructure.extraction.AuthTokenPropagator;
import io.spring.infrastructure.extraction.ExtractionProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Consumer-side check of {@link UserServiceClient} against the canonical internal User API; the
 * JSON fixtures under {@code src/test/resources/user-service-stubs} mirror phase-5-user.md section
 * 2.1.
 */
public class UserServiceClientTest {
  private static final String BASE = "http://localhost:8084";
  private static final String U1 = "u1000000-0000-0000-0000-000000000001";
  private static final String U2 = "u2000000-0000-0000-0000-000000000002";

  private UserServiceClient client;
  private MockRestServiceServer server;

  @BeforeEach
  public void setUp() {
    client =
        new UserServiceClient(
            new RestTemplateBuilder(), new ExtractionProperties(), new AuthTokenPropagator());
    server = MockRestServiceServer.bindTo(client.getRestTemplate()).build();
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void single_lookups_read_without_credentials_and_map_404_to_empty() {
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1))
        .andExpect(method(HttpMethod.GET))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess(stub("user-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/users/by-username/john"))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess(stub("user-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/users/by-email/john@jacob.com"))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess(stub("user-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/users/by-username/nope"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    UserRowDto row = client.findById(U1).get();
    Assertions.assertEquals("john", row.getUsername());
    Assertions.assertEquals("john@jacob.com", row.getEmail());
    Assertions.assertEquals("bio", row.getBio());
    Assertions.assertEquals("img", row.getImage());
    Assertions.assertEquals(U1, client.findByUsername("john").get().getId());
    Assertions.assertEquals(U1, client.findByEmail("john@jacob.com").get().getId());
    Assertions.assertEquals(Optional.empty(), client.findByUsername("nope"));
    server.verify();
  }

  @Test
  public void find_by_ids_sends_one_batch_and_skips_empty_batches() {
    server
        .expect(once(), requestTo(BASE + "/internal/users?ids=" + U1 + "," + U2))
        .andExpect(method(HttpMethod.GET))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess(stub("users-response.json"), MediaType.APPLICATION_JSON));

    List<UserRowDto> rows = client.findByIds(Arrays.asList(U1, U2));
    Assertions.assertEquals(2, rows.size());
    Assertions.assertEquals("jane", rows.get(1).getUsername());
    Assertions.assertTrue(client.findByIds(Collections.emptyList()).isEmpty());
    server.verify();
  }

  @Test
  public void follow_reads_go_without_credentials() {
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1 + "/following?ids=" + U2 + ",x"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess("{\"followingIds\":[\"" + U2 + "\"]}", MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1 + "/followed"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess("{\"followedIds\":[\"" + U2 + "\",\"x\"]}", MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1 + "/follows/" + U2))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"following\":true}", MediaType.APPLICATION_JSON));

    Assertions.assertEquals(
        Collections.singletonList(U2), client.followingIds(U1, Arrays.asList(U2, "x")));
    Assertions.assertTrue(client.followingIds(U1, Collections.emptyList()).isEmpty());
    Assertions.assertEquals(Arrays.asList(U2, "x"), client.followedIds(U1));
    Assertions.assertTrue(client.isFollowing(U1, U2));
    server.verify();
  }

  @Test
  public void writes_forward_the_caller_token_and_ship_only_the_hash() {
    authenticatedRequest("Token jwt-123");
    User user = new User("john@jacob.com", "john", "$2a$10$hash", "bio", "img");
    server
        .expect(once(), requestTo(BASE + "/internal/users"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Token jwt-123"))
        .andExpect(header("Content-Type", Matchers.startsWith("application/json")))
        .andExpect(jsonPath("$.id").value(user.getId()))
        .andExpect(jsonPath("$.username").value("john"))
        .andExpect(jsonPath("$.email").value("john@jacob.com"))
        .andExpect(jsonPath("$.passwordHash").value("$2a$10$hash"))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.bio").value("bio"))
        .andExpect(jsonPath("$.image").value("img"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(stub("user-response.json")));
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + user.getId()))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Token jwt-123"))
        .andExpect(jsonPath("$.username").value("john"))
        .andExpect(jsonPath("$.passwordHash").value("$2a$10$hash"))
        .andExpect(jsonPath("$.id").doesNotExist())
        .andRespond(withSuccess(stub("user-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1 + "/follows/" + U2))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Token jwt-123"))
        .andRespond(withNoContent());
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1 + "/follows/" + U2))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("Authorization", "Token jwt-123"))
        .andRespond(withNoContent());

    Assertions.assertEquals(U1, client.create(user).getId());
    Assertions.assertEquals("john", client.update(user).getUsername());
    client.follow(U1, U2);
    client.unfollow(U1, U2);
    server.verify();
  }

  @Test
  public void credentials_verify_posts_the_raw_password_without_token_and_maps_404_to_false() {
    authenticatedRequest("Token jwt-123");
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1 + "/credentials/verify"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(headerDoesNotExist("Authorization"))
        .andExpect(content().json("{\"password\":\"secret\"}"))
        .andRespond(withSuccess("{\"valid\":true}", MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1 + "/credentials/verify"))
        .andRespond(withSuccess("{\"valid\":false}", MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/users/nope/credentials/verify"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    Assertions.assertTrue(client.verifyCredentials(U1, "secret"));
    Assertions.assertFalse(client.verifyCredentials(U1, "wrong"));
    Assertions.assertFalse(client.verifyCredentials("nope", "secret"));
    server.verify();
  }

  @Test
  public void reads_retry_once_on_connect_failure_and_503_but_not_on_other_errors() {
    server
        .expect(times(2), requestTo(BASE + "/internal/users/" + U1))
        .andRespond(
            request -> {
              throw new ResourceAccessException("boom", new ConnectException("refused"));
            });
    server
        .expect(times(2), requestTo(BASE + "/internal/users/" + U1 + "/followed"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server.expect(once(), requestTo(BASE + "/internal/users/" + U2)).andRespond(withServerError());

    Assertions.assertThrows(UserServiceException.class, () -> client.findById(U1));
    Assertions.assertThrows(UserServiceException.class, () -> client.followedIds(U1));
    Assertions.assertThrows(UserServiceException.class, () -> client.findById(U2));
    server.verify();
  }

  @Test
  public void reads_recover_when_the_retry_succeeds() {
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1))
        .andRespond(withSuccess(stub("user-response.json"), MediaType.APPLICATION_JSON));

    Assertions.assertEquals("john", client.findById(U1).get().getUsername());
    server.verify();
  }

  @Test
  public void writes_are_never_retried() {
    User user = new User("john@jacob.com", "john", "hash", "", "");
    server
        .expect(once(), requestTo(BASE + "/internal/users"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1 + "/follows/" + U2))
        .andRespond(
            request -> {
              throw new ResourceAccessException("boom", new ConnectException("refused"));
            });
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + U1 + "/credentials/verify"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(once(), requestTo(BASE + "/internal/users/" + user.getId()))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

    Assertions.assertThrows(UserServiceException.class, () -> client.create(user));
    Assertions.assertThrows(UserServiceException.class, () -> client.follow(U1, U2));
    Assertions.assertThrows(UserServiceException.class, () -> client.verifyCredentials(U1, "x"));
    UserServiceException e =
        Assertions.assertThrows(UserServiceException.class, () -> client.update(user));
    Assertions.assertTrue(e.getMessage().contains("status 422"));
    Assertions.assertFalse(e.getMessage().contains("hash"));
    server.verify();
  }

  @Test
  public void retryable_classification_matches_the_article_client() {
    Assertions.assertTrue(
        UserServiceClient.retryable(
            new ResourceAccessException("x", new ConnectException("refused"))));
    Assertions.assertTrue(
        UserServiceClient.retryable(
            new ResourceAccessException("x", new SocketTimeoutException("connect timed out"))));
    Assertions.assertFalse(
        UserServiceClient.retryable(
            new ResourceAccessException("x", new SocketTimeoutException("Read timed out"))));
  }

  private static void authenticatedRequest(String authorization) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", authorization);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private static String stub(String name) {
    try (InputStream in =
        UserServiceClientTest.class
            .getClassLoader()
            .getResourceAsStream("user-service-stubs/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing stub " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
