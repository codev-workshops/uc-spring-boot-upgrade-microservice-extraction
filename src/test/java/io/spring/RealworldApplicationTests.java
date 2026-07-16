package io.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(
    properties = {
      "jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret-test-secret-123456789",
      "internal.service-key=test-internal-service-key"
    })
public class RealworldApplicationTests {

  @Test
  public void contextLoads() {}
}
