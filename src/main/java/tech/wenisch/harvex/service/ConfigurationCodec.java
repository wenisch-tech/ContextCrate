package tech.wenisch.harvex.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.domain.CrawlConfiguration;
import tech.wenisch.harvex.domain.CrawlConfigurationCompatibility;

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
      // First try to read as new format
      return mapper.readValue(json, CrawlConfiguration.class);
    } catch (JsonProcessingException e) {
      try {
        // If that fails, try to read as old format with backward compatibility
        CrawlConfigurationCompatibility compatibility = mapper.readValue(json, CrawlConfigurationCompatibility.class);
        return compatibility.getConfig();
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException("Invalid stored crawl configuration", ex);
      }
    }
  }
}
