package io.spring.infrastructure.extraction.tag;

import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ExtractionProperties.Fallback;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public class TagExtractionPropertiesTest {
  @Test
  public void tag_route_defaults_to_off_monolith_and_port_8083() {
    DomainRoute tag = new ExtractionProperties().getTag();
    Assertions.assertFalse(tag.isEnabled());
    Assertions.assertEquals(ReadMode.MONOLITH, tag.getRead());
    Assertions.assertEquals(WriteMode.MONOLITH, tag.getWrite());
    Assertions.assertEquals(Fallback.MONOLITH, tag.getFallback());
    Assertions.assertEquals(URI.create("http://localhost:8083"), tag.getBaseUrl());
    Assertions.assertEquals(Duration.ofMillis(500), tag.getConnectTimeout());
    Assertions.assertEquals(Duration.ofMillis(1500), tag.getReadTimeout());
    Assertions.assertFalse(tag.readsRemote());
    Assertions.assertFalse(tag.shadows());
    Assertions.assertFalse(tag.writesRemote());
    Assertions.assertTrue(tag.monolithAuthoritative());
  }

  @Test
  public void tag_route_binds_independently_of_favorite_and_comment() {
    Map<String, Object> source = new HashMap<>();
    source.put("extraction.tag.enabled", "true");
    source.put("extraction.tag.read", "shadow");
    source.put("extraction.tag.write", "dual-write");
    source.put("extraction.tag.fallback", "empty");
    source.put("extraction.tag.base-url", "http://article:9000");
    source.put("extraction.tag.read-timeout", "2s");
    ExtractionProperties properties =
        new Binder(new MapConfigurationPropertySource(source))
            .bind("extraction", ExtractionProperties.class)
            .get();

    DomainRoute tag = properties.getTag();
    Assertions.assertTrue(tag.shadows());
    Assertions.assertTrue(tag.writesRemote());
    Assertions.assertTrue(tag.monolithAuthoritative());
    Assertions.assertEquals(Fallback.EMPTY, tag.getFallback());
    Assertions.assertEquals("http://article:9000", tag.getBaseUrl().toString());
    Assertions.assertEquals(Duration.ofSeconds(2), tag.getReadTimeout());
    Assertions.assertFalse(properties.getFavorite().isEnabled());
    Assertions.assertFalse(properties.getComment().isEnabled());
    Assertions.assertEquals(
        "http://localhost:8082", properties.getComment().getBaseUrl().toString());
  }
}
