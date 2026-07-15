package io.spring;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ClientConfig {
  @Bean
  public RestTemplate restTemplate(RestTemplateBuilder builder, ObjectMapper objectMapper) {
    ObjectMapper clientObjectMapper = objectMapper.copy();
    clientObjectMapper.disable(DeserializationFeature.UNWRAP_ROOT_VALUE);
    return builder
        .setConnectTimeout(Duration.ofSeconds(2))
        .setReadTimeout(Duration.ofSeconds(2))
        .messageConverters(new MappingJackson2HttpMessageConverter(clientObjectMapper))
        .build();
  }
}
