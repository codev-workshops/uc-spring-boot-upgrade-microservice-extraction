package io.spring.infrastructure.extraction.favorite;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.spring.application.favorite.dto.FavoriteCountDto;
import io.spring.application.favorite.dto.FavoriteDto;
import io.spring.application.favorite.dto.UserFavoritesDto;
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
 * Consumer-side check of {@link FavoriteServiceClient} against the canonical internal API; the JSON
 * fixtures under {@code src/test/resources/favorite-service-stubs} are copied verbatim from
 * phase-1-favorite.md section 2.1.
 */
public class FavoriteServiceClientTest {
  private static final String BASE = "http://localhost:8081";

  private FavoriteServiceClient client;
  private MockRestServiceServer server;

  @BeforeEach
  public void setUp() {
    client =
        new FavoriteServiceClient(
            new RestTemplateBuilder(), new ExtractionProperties(), new AuthTokenPropagator());
    server = MockRestServiceServer.bindTo(client.getRestTemplate()).build();
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void counts_posts_the_id_batch_without_credentials() {
    server
        .expect(once(), requestTo(BASE + "/internal/favorites/counts"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(headerDoesNotExist("Authorization"))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(stub("counts-request.json")))
        .andRespond(withSuccess(stub("counts-response.json"), MediaType.APPLICATION_JSON));

    List<FavoriteCountDto> counts = client.counts(Arrays.asList("a", "b"));

    server.verify();
    Assertions.assertEquals(
        Arrays.asList(new FavoriteCountDto("a", 2), new FavoriteCountDto("b", 0)), counts);
  }

  @Test
  public void counts_short_circuits_an_empty_batch() {
    Assertions.assertTrue(client.counts(Collections.emptyList()).isEmpty());
    server.verify();
  }

  @Test
  public void query_returns_the_favorited_subset() {
    server
        .expect(once(), requestTo(BASE + "/internal/favorites/query"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(headerDoesNotExist("Authorization"))
        .andExpect(content().json(stub("query-request.json")))
        .andRespond(withSuccess(stub("query-response.json"), MediaType.APPLICATION_JSON));

    UserFavoritesDto dto = client.userFavorites("u", Arrays.asList("a", "b"));

    server.verify();
    Assertions.assertEquals("u", dto.getUserId());
    Assertions.assertEquals(Collections.singletonList("a"), dto.getArticleIds());
  }

  @Test
  public void by_user_lists_all_favorited_article_ids() {
    server
        .expect(once(), requestTo(BASE + "/internal/favorites/by-user/u/article-ids"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(stub("by-user-response.json"), MediaType.APPLICATION_JSON));

    Assertions.assertEquals(
        Arrays.asList("a", "c"), client.articleIdsFavoritedBy("u").getArticleIds());
    server.verify();
  }

  @Test
  public void favorite_forwards_the_callers_token_and_is_not_retried() {
    withIncomingToken("Token jwt-1");
    server
        .expect(once(), requestTo(BASE + "/internal/favorites/a/u"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Token jwt-1"))
        .andRespond(withSuccess(stub("favorite-response.json"), MediaType.APPLICATION_JSON));

    FavoriteDto dto = client.favorite("a", "u");

    server.verify();
    Assertions.assertEquals(new FavoriteDto("a", "u", true), dto);
  }

  @Test
  public void unfavorite_forwards_the_callers_token_and_accepts_204() {
    withIncomingToken("Token jwt-2");
    server
        .expect(once(), requestTo(BASE + "/internal/favorites/a/u"))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("Authorization", "Token jwt-2"))
        .andRespond(withNoContent());

    client.unfavorite("a", "u");
    server.verify();
  }

  @Test
  public void write_failure_is_wrapped_and_not_retried() {
    server
        .expect(once(), requestTo(BASE + "/internal/favorites/a/u"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    Assertions.assertThrows(FavoriteServiceException.class, () -> client.favorite("a", "u"));
    server.verify();
  }

  @Test
  public void read_is_retried_once_on_503_then_wrapped() {
    server
        .expect(times(2), requestTo(BASE + "/internal/favorites/counts"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    Assertions.assertThrows(
        FavoriteServiceException.class, () -> client.counts(Collections.singletonList("a")));
    server.verify();
  }

  @Test
  public void read_is_retried_once_on_connect_failure() {
    server
        .expect(once(), requestTo(BASE + "/internal/favorites/counts"))
        .andRespond(
            request -> {
              throw new ConnectException("Connection refused");
            });
    server
        .expect(once(), requestTo(BASE + "/internal/favorites/counts"))
        .andRespond(withSuccess(stub("counts-response.json"), MediaType.APPLICATION_JSON));

    Assertions.assertEquals(2, client.counts(Arrays.asList("a", "b")).size());
    server.verify();
  }

  @Test
  public void read_timeout_and_5xx_are_not_retried() {
    server
        .expect(once(), requestTo(BASE + "/internal/favorites/counts"))
        .andRespond(
            request -> {
              throw new SocketTimeoutException("Read timed out");
            });
    Assertions.assertThrows(
        FavoriteServiceException.class, () -> client.counts(Collections.singletonList("a")));
    server.verify();

    server.reset();
    server
        .expect(once(), requestTo(BASE + "/internal/favorites/counts"))
        .andRespond(withServerError());
    Assertions.assertThrows(
        FavoriteServiceException.class, () -> client.counts(Collections.singletonList("a")));
    server.verify();
  }

  @Test
  public void retryable_classifies_connect_errors_only() {
    Assertions.assertTrue(
        FavoriteServiceClient.retryable(
            new ResourceAccessException("x", new ConnectException("refused"))));
    Assertions.assertTrue(
        FavoriteServiceClient.retryable(
            new ResourceAccessException("x", new SocketTimeoutException("connect timed out"))));
    Assertions.assertFalse(
        FavoriteServiceClient.retryable(
            new ResourceAccessException("x", new SocketTimeoutException("Read timed out"))));
  }

  private static void withIncomingToken(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", token);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  static String stub(String name) {
    try (InputStream in =
        FavoriteServiceClientTest.class
            .getClassLoader()
            .getResourceAsStream("favorite-service-stubs/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing stub " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
