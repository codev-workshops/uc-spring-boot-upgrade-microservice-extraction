package io.spring.graphql;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;

@Getter
@Setter
@ConfigurationProperties(prefix = "feature.graphql")
public class GraphQlFeatureProperties {
  private boolean enabled = true;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate deprecationDate = LocalDate.of(2026, 10, 19);

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate sunsetDate = LocalDate.of(2027, 1, 19);
}
