package io.spring.infrastructure.extraction;

import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Feature flags for the strangler-pattern extraction, one {@link DomainRoute} per domain. All
 * defaults are OFF so that an unconfigured monolith behaves exactly as before the extraction.
 */
@ConfigurationProperties(prefix = "extraction")
@Validated
@Getter
@Setter
public class ExtractionProperties {
  private DomainRoute favorite = new DomainRoute();
  private DomainRoute comment = new DomainRoute(URI.create("http://localhost:8082"));
  private DomainRoute tag = new DomainRoute(URI.create("http://localhost:8083"));
  private DomainRoute article = new DomainRoute();
  private DomainRoute user = new DomainRoute();

  @Getter
  @Setter
  public static class DomainRoute {
    private boolean enabled = false;
    private ReadMode read = ReadMode.MONOLITH;
    private WriteMode write = WriteMode.MONOLITH;
    private Fallback fallback = Fallback.MONOLITH;
    private URI baseUrl = URI.create("http://localhost:8081");
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofMillis(1500);

    public DomainRoute() {}

    public DomainRoute(URI baseUrl) {
      this.baseUrl = baseUrl;
    }

    public boolean readsRemote() {
      return enabled && read == ReadMode.EXTRACTED;
    }

    public boolean shadows() {
      return enabled && read == ReadMode.SHADOW;
    }

    public boolean writesRemote() {
      return enabled && write != WriteMode.MONOLITH;
    }

    public boolean writesLocal() {
      return !enabled || write != WriteMode.EXTRACTED;
    }

    /** True while the monolith table is the source of truth for this domain. */
    public boolean monolithAuthoritative() {
      return writesLocal();
    }
  }

  public enum ReadMode {
    MONOLITH,
    EXTRACTED,
    SHADOW
  }

  public enum WriteMode {
    MONOLITH,
    DUAL_WRITE,
    EXTRACTED
  }

  public enum Fallback {
    MONOLITH,
    EMPTY,
    FAIL
  }
}
