package io.spring.infrastructure.extraction.user;

import io.spring.application.data.UserData;
import io.spring.application.user.dto.UserRowDto;
import io.spring.core.user.User;
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

public class UserExtractionPropertiesTest {
  @Test
  public void user_route_defaults_to_off_monolith_and_port_8084() {
    DomainRoute user = new ExtractionProperties().getUser();
    Assertions.assertFalse(user.isEnabled());
    Assertions.assertEquals(ReadMode.MONOLITH, user.getRead());
    Assertions.assertEquals(WriteMode.MONOLITH, user.getWrite());
    Assertions.assertEquals(Fallback.MONOLITH, user.getFallback());
    Assertions.assertEquals(URI.create("http://localhost:8084"), user.getBaseUrl());
    Assertions.assertEquals(Duration.ofMillis(500), user.getConnectTimeout());
    Assertions.assertEquals(Duration.ofMillis(1500), user.getReadTimeout());
    Assertions.assertFalse(user.readsRemote());
    Assertions.assertFalse(user.shadows());
    Assertions.assertFalse(user.writesRemote());
    Assertions.assertTrue(user.monolithAuthoritative());
  }

  @Test
  public void user_route_binds_independently_of_the_other_domains() {
    Map<String, Object> source = new HashMap<>();
    source.put("extraction.user.enabled", "true");
    source.put("extraction.user.read", "extracted");
    source.put("extraction.user.write", "dual-write");
    source.put("extraction.user.fallback", "empty");
    source.put("extraction.user.base-url", "http://user:9000");
    source.put("extraction.user.read-timeout", "2s");
    ExtractionProperties properties =
        new Binder(new MapConfigurationPropertySource(source))
            .bind("extraction", ExtractionProperties.class)
            .get();

    DomainRoute user = properties.getUser();
    Assertions.assertTrue(user.readsRemote());
    Assertions.assertTrue(user.writesRemote());
    Assertions.assertTrue(user.writesLocal());
    Assertions.assertTrue(user.monolithAuthoritative());
    Assertions.assertEquals(Fallback.EMPTY, user.getFallback());
    Assertions.assertEquals("http://user:9000", user.getBaseUrl().toString());
    Assertions.assertEquals(Duration.ofSeconds(2), user.getReadTimeout());
    Assertions.assertFalse(properties.getArticle().isEnabled());
    Assertions.assertEquals(
        "http://localhost:8083", properties.getArticle().getBaseUrl().toString());
    Assertions.assertFalse(properties.getFavorite().isEnabled());
    Assertions.assertFalse(properties.getComment().isEnabled());
    Assertions.assertFalse(properties.getTag().isEnabled());
  }

  @Test
  public void remote_rows_map_to_user_data_and_to_a_hashless_user() {
    UserRowDto row = new UserRowDto("u1", "john", "john@jacob.com", "bio", "img");

    UserData data = RemoteUserQueryAdapter.toData(row);
    Assertions.assertEquals("u1", data.getId());
    Assertions.assertEquals("john", data.getUsername());
    Assertions.assertEquals("john@jacob.com", data.getEmail());
    Assertions.assertEquals("bio", data.getBio());
    Assertions.assertEquals("img", data.getImage());

    User user = RemoteUserQueryAdapter.toUser(row);
    Assertions.assertEquals("u1", user.getId());
    Assertions.assertEquals("john", user.getUsername());
    Assertions.assertEquals("", user.getPassword());
    user.update("", "", "", "new bio", "");
    Assertions.assertEquals("", user.getPassword());
    Assertions.assertEquals("new bio", user.getBio());
  }
}
