package tech.wenisch.contextcrate.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
  @Bean
  OpenAPI contextCrateOpenApi() {
    return new OpenAPI().info(new Info()
        .title("ContextCrate API")
        .description("Crate-scoped crawling, extraction, search, answering, and administration API")
        .version("v1"));
  }
}
