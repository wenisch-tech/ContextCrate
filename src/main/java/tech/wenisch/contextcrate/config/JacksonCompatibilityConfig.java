package tech.wenisch.contextcrate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Internal serializer for portable queue and backup contracts. Spring Boot 4 uses Jackson 3 for
 * HTTP.
 */
@Configuration
public class JacksonCompatibilityConfig {
  @Bean
  ObjectMapper pipelineObjectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
