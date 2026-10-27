package io.spring.articles.infrastructure.client;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
public class UserServiceClientConfig {

  @Bean
  public RestClientCustomizer userServiceTimeouts(
      @Value("${user-service.connect-timeout:2s}") Duration connectTimeout,
      @Value("${user-service.read-timeout:5s}") Duration readTimeout) {
    return builder -> {
      SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(connectTimeout);
      requestFactory.setReadTimeout(readTimeout);
      builder.requestFactory(requestFactory);
    };
  }
}
