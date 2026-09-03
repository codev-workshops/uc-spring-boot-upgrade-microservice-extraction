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

public class ExtractionPropertiesTest {
  @Test
  public void defaults_are_all_off_and_monolith() {
    ExtractionProperties.DomainRoute route = new ExtractionProperties().getFavorite();
    Assertions.assertFalse(route.isEnabled());
    Assertions.assertEquals(ReadMode.MONOLITH, route.getRead());
    Assertions.assertEquals(WriteMode.MONOLITH, route.getWrite());
    Assertions.assertEquals(Fallback.MONOLITH, route.getFallback());
    Assertions.assertEquals("http://localhost:8081", route.getBaseUrl().toString());
    Assertions.assertEquals(Duration.ofMillis(500), route.getConnectTimeout());
    Assertions.assertEquals(Duration.ofMillis(1500), route.getReadTimeout());
    Assertions.assertFalse(route.readsRemote());
    Assertions.assertFalse(route.writesRemote());
    Assertions.assertTrue(route.monolithAuthoritative());
  }

  @Test
  public void binds_kebab_case_modes() {
    Map<String, Object> source = new HashMap<>();
    source.put("extraction.favorite.enabled", "true");
    source.put("extraction.favorite.read", "shadow");
    source.put("extraction.favorite.write", "dual-write");
    source.put("extraction.favorite.fallback", "empty");
    source.put("extraction.favorite.base-url", "http://favorite:9000");
    source.put("extraction.favorite.read-timeout", "2s");

    ExtractionProperties properties =
        new Binder(new MapConfigurationPropertySource(source))
            .bind("extraction", ExtractionProperties.class)
            .get();

    ExtractionProperties.DomainRoute route = properties.getFavorite();
    Assertions.assertTrue(route.isEnabled());
    Assertions.assertEquals(ReadMode.SHADOW, route.getRead());
    Assertions.assertEquals(WriteMode.DUAL_WRITE, route.getWrite());
    Assertions.assertEquals(Fallback.EMPTY, route.getFallback());
    Assertions.assertEquals("http://favorite:9000", route.getBaseUrl().toString());
    Assertions.assertEquals(Duration.ofSeconds(2), route.getReadTimeout());
    Assertions.assertTrue(route.shadows());
    Assertions.assertTrue(route.writesRemote());
    Assertions.assertTrue(route.monolithAuthoritative());
    Assertions.assertFalse(properties.getComment().isEnabled());
  }

  @Test
  public void flag_disabled_forces_monolith_regardless_of_modes() {
    ExtractionProperties.DomainRoute route = new ExtractionProperties.DomainRoute();
    route.setRead(ReadMode.EXTRACTED);
    route.setWrite(WriteMode.EXTRACTED);
    Assertions.assertFalse(route.readsRemote());
    Assertions.assertFalse(route.writesRemote());
    Assertions.assertTrue(route.writesLocal());

    route.setEnabled(true);
    Assertions.assertTrue(route.readsRemote());
    Assertions.assertTrue(route.writesRemote());
    Assertions.assertFalse(route.monolithAuthoritative());
  }
}
