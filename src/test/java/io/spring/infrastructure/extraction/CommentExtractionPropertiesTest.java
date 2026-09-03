package io.spring.infrastructure.extraction;

import io.spring.infrastructure.extraction.ExtractionProperties.Fallback;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public class CommentExtractionPropertiesTest {
  @Test
  public void comment_route_defaults_are_off_and_point_at_8082() {
    ExtractionProperties.DomainRoute route = new ExtractionProperties().getComment();
    Assertions.assertFalse(route.isEnabled());
    Assertions.assertEquals(ReadMode.MONOLITH, route.getRead());
    Assertions.assertEquals(WriteMode.MONOLITH, route.getWrite());
    Assertions.assertEquals(Fallback.MONOLITH, route.getFallback());
    Assertions.assertEquals("http://localhost:8082", route.getBaseUrl().toString());
    Assertions.assertEquals(Duration.ofMillis(500), route.getConnectTimeout());
    Assertions.assertEquals(Duration.ofMillis(1500), route.getReadTimeout());
    Assertions.assertTrue(route.monolithAuthoritative());
  }

  @Test
  public void comment_route_binds_independently_of_favorite() {
    Map<String, Object> source = new HashMap<>();
    source.put("extraction.comment.enabled", "true");
    source.put("extraction.comment.read", "extracted");
    source.put("extraction.comment.write", "dual-write");
    source.put("extraction.comment.fallback", "fail");
    source.put("extraction.comment.base-url", "http://comment:9000");
    source.put("extraction.comment.connect-timeout", "1s");
    ExtractionProperties properties =
        new Binder(new MapConfigurationPropertySource(source))
            .bind("extraction", ExtractionProperties.class)
            .get();

    ExtractionProperties.DomainRoute route = properties.getComment();
    Assertions.assertTrue(route.readsRemote());
    Assertions.assertEquals(WriteMode.DUAL_WRITE, route.getWrite());
    Assertions.assertEquals(Fallback.FAIL, route.getFallback());
    Assertions.assertEquals("http://comment:9000", route.getBaseUrl().toString());
    Assertions.assertEquals(Duration.ofSeconds(1), route.getConnectTimeout());
    Assertions.assertFalse(properties.getFavorite().isEnabled());
    Assertions.assertEquals(
        "http://localhost:8081", properties.getFavorite().getBaseUrl().toString());
  }
}
