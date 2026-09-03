package io.spring.infrastructure.extraction;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the extraction feature flags. Kept off {@code RealWorldApplication} so that
 * {@code @WebMvcTest} / {@code @MybatisTest} slices never see the routing beans.
 */
@Configuration
@EnableConfigurationProperties(ExtractionProperties.class)
public class ExtractionConfig {}
