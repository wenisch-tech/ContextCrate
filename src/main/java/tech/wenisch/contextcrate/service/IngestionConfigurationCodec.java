package tech.wenisch.contextcrate.service;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.domain.*;

@Component
public class IngestionConfigurationCodec {
  private final ObjectMapper mapper;
  private final ConfigurationCodec crawlCodec;

  public IngestionConfigurationCodec(ObjectMapper mapper, ConfigurationCodec crawlCodec) {
    this.mapper = mapper;
    this.crawlCodec = crawlCodec;
  }

  public String write(IngestionConfiguration value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public IngestionConfiguration read(String json, ConnectorType type) {
    try {
      JsonNode root = mapper.readTree(json);
      if (root.has("scope")) return IngestionConfiguration.web(crawlCodec.read(json));
      return mapper.treeToValue(root, IngestionConfiguration.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored ingestion configuration", e);
    }
  }
}
