package io.spring.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * Compares the response envelope of an endpoint between the monolith path (feature flag off) and
 * the future extracted-service path (feature flag on).
 *
 * <p>The harness normalizes the volatile parts of a response (identifiers and timestamps) so that
 * two runs of the same scenario are comparable, and asserts the result against a golden envelope
 * recorded under {@code src/test/resources/golden}. In Phase 0 only the monolith side can be
 * exercised; {@link #supports(RoutePath)} reports whether a route is available so tests can skip
 * the extracted side instead of failing.
 */
public class ParallelRunHarness {
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final List<String> VOLATILE_FIELDS =
      Collections.unmodifiableList(
          new ArrayList<>(java.util.Arrays.asList("createdAt", "updatedAt", "cursor")));
  private static final String GOLDEN_ROOT = "golden/";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final MockMvc mockMvc;
  private final boolean extractedRouteEnabled;

  public ParallelRunHarness(MockMvc mockMvc, boolean extractedRouteEnabled) {
    this.mockMvc = mockMvc;
    this.extractedRouteEnabled = extractedRouteEnabled;
  }

  /** Whether the given side of the migration can be exercised in the current configuration. */
  public boolean supports(RoutePath route) {
    return route == RoutePath.MONOLITH || extractedRouteEnabled;
  }

  /** Performs the request against the given route and returns its normalized JSON envelope. */
  public String captureEnvelope(RoutePath route, RequestBuilder request) throws Exception {
    if (!supports(route)) {
      throw new IllegalStateException("route " + route + " is not available");
    }
    MvcResult result = mockMvc.perform(request).andReturn();
    Assertions.assertEquals(
        200, result.getResponse().getStatus(), "unexpected status for route " + route);
    String body = result.getResponse().getContentAsString();
    return normalize(body);
  }

  /** Asserts that both routes produced the same envelope. */
  public void assertEnvelopesMatch(String monolithEnvelope, String extractedEnvelope) {
    Assertions.assertEquals(monolithEnvelope, extractedEnvelope, "response envelopes diverged");
  }

  /**
   * Asserts the envelope against the golden file {@code src/test/resources/golden/<name>.json}. Run
   * with {@code -Dharness.record=true} to print the current envelope when a golden has to be
   * refreshed on purpose.
   */
  public void assertMatchesGolden(String name, String envelope) {
    String resource = GOLDEN_ROOT + name + ".json";
    if (Boolean.getBoolean("harness.record")) {
      System.out.println("recorded envelope for " + resource + ":\n" + envelope);
      return;
    }
    Assertions.assertEquals(normalize(readResource(resource)), envelope, "golden " + resource);
  }

  String normalize(String json) {
    try {
      return objectMapper
          .writerWithDefaultPrettyPrinter()
          .writeValueAsString(normalizeNode(objectMapper.readTree(json)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private JsonNode normalizeNode(JsonNode node) {
    if (node.isObject()) {
      ObjectNode source = (ObjectNode) node;
      List<String> fieldNames = new ArrayList<>();
      Iterator<String> iterator = source.fieldNames();
      while (iterator.hasNext()) {
        fieldNames.add(iterator.next());
      }
      Collections.sort(fieldNames);
      ObjectNode normalized = objectMapper.createObjectNode();
      for (String fieldName : fieldNames) {
        JsonNode value = source.get(fieldName);
        if (VOLATILE_FIELDS.contains(fieldName)) {
          normalized.set(fieldName, TextNode.valueOf("<volatile>"));
        } else {
          normalized.set(fieldName, normalizeNode(value));
        }
      }
      return normalized;
    }
    if (node.isArray()) {
      ArrayNode normalized = objectMapper.createArrayNode();
      node.forEach(element -> normalized.add(normalizeNode(element)));
      return normalized;
    }
    if (node.isTextual()) {
      return TextNode.valueOf(UUID_PATTERN.matcher(node.textValue()).replaceAll("<uuid>"));
    }
    return node;
  }

  private String readResource(String resource) {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(
            "missing golden file " + resource + "; rerun with -Dharness.record=true to capture it");
      }
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
