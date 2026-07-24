package tech.wenisch.contextcrate.service;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.domain.*;

@Component
public class SourceConfigurationCodec {
  private final ObjectMapper mapper;
  private final ConfigurationCodec crawlCodec;

  public SourceConfigurationCodec(ObjectMapper mapper, ConfigurationCodec crawlCodec) {
    this.mapper = mapper;
    this.crawlCodec = crawlCodec;
  }

  public String write(SourceConfiguration value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public SourceConfiguration read(String json, ConnectorType type) {
    try {
      JsonNode root = mapper.readTree(json);
      if (root.has("scope")) {
        CrawlConfiguration legacy = crawlCodec.read(json);
        return SourceConfiguration.https(origin(legacy.scope().seedUrl()));
      }
      // Version 9 stored web authentication on the source. Authentication now belongs to each
      // HTTPS ingestion job; silently discard that legacy field when reading old sources.
      if (root.path("website").isObject()) {
        var website = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("website");
        if (website.has("origin")) website.set("url", website.get("origin"));
        website.remove("origin");
        website.remove("authentication");
      }
      if (root.path("git").isObject()) {
        var git = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("git");
        git.remove("username");
        git.remove("token");
      }
      return mapper.treeToValue(root, SourceConfiguration.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored source configuration", e);
    }
  }

  private static String origin(String value) {
    try {
      var uri = java.net.URI.create(value);
      int port = uri.getPort();
      return uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
    } catch (Exception e) {
      return value;
    }
  }
}
