package io.spring.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class GraphQlDeprecationFilterTest {

  @Test
  public void enabledGraphQlRequestHasDeprecationHeadersAndContinues() throws Exception {
    FilterResult result = invokeFilter(true, "POST", "/graphql");

    assertNotNull(result.chain.getRequest());
    assertEquals("@1792368000", result.response.getHeader("Deprecation"));
    assertEquals("Tue, 19 Jan 2027 00:00:00 GMT", result.response.getHeader("Sunset"));
  }

  @Test
  public void disabledGraphQlRequestReturnsGoneWithHeadersAndDoesNotContinue() throws Exception {
    FilterResult result = invokeFilter(false, "POST", "/graphql");

    assertEquals(410, result.response.getStatus());
    assertEquals("application/json", result.response.getContentType());
    assertTrue(result.response.getContentAsString().contains("\"errors\""));
    assertNull(result.chain.getRequest());
    assertEquals("@1792368000", result.response.getHeader("Deprecation"));
    assertEquals("Tue, 19 Jan 2027 00:00:00 GMT", result.response.getHeader("Sunset"));
  }

  @Test
  public void disabledGraphQlRelatedPathsReturnGone() throws Exception {
    for (String path : new String[] {"/graphql/", "/graphiql", "/schema.json"}) {
      FilterResult result = invokeFilter(false, "GET", path);

      assertEquals(410, result.response.getStatus(), path);
      assertNotNull(result.response.getHeader("Deprecation"), path);
      assertNotNull(result.response.getHeader("Sunset"), path);
      assertNull(result.chain.getRequest(), path);
    }
  }

  @Test
  public void enabledRestRequestHasNoDeprecationHeader() throws Exception {
    FilterResult result = invokeFilter(true, "GET", "/articles");

    assertNotNull(result.chain.getRequest());
    assertNull(result.response.getHeader("Deprecation"));
  }

  @Test
  public void disabledRestRequestContinues() throws Exception {
    FilterResult result = invokeFilter(false, "GET", "/tags");

    assertNotNull(result.chain.getRequest());
    assertEquals(200, result.response.getStatus());
    assertNull(result.response.getHeader("Deprecation"));
  }

  private FilterResult invokeFilter(boolean enabled, String method, String path) throws Exception {
    GraphQlFeatureProperties properties = new GraphQlFeatureProperties();
    properties.setEnabled(enabled);
    properties.setDeprecationDate(LocalDate.of(2026, 10, 19));
    properties.setSunsetDate(LocalDate.of(2027, 1, 19));

    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    new GraphQlDeprecationFilter(properties).doFilter(request, response, chain);
    return new FilterResult(response, chain);
  }

  private static class FilterResult {

    private final MockHttpServletResponse response;
    private final MockFilterChain chain;

    private FilterResult(MockHttpServletResponse response, MockFilterChain chain) {
      this.response = response;
      this.chain = chain;
    }
  }
}
