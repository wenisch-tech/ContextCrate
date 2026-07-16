package tech.wenisch.harvex.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.domain.CrawlConfiguration;

@Component
public class ConfigurationCodec {
  private final ObjectMapper mapper;

  public ConfigurationCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(CrawlConfiguration c) {
    try {
      return mapper.writeValueAsString(c);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public CrawlConfiguration read(String json) {
    try {
      return mapper.readValue(json, CrawlConfiguration.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored crawl configuration", e);
    }
  }
}
