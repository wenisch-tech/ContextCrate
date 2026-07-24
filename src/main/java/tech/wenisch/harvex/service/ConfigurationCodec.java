package tech.wenisch.harvex.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
      ObjectNode root = (ObjectNode) mapper.readTree(json);
      migrateAuthentication(root);
      return mapper.treeToValue(root, CrawlConfiguration.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored crawl configuration", e);
    }
  }

  private void migrateAuthentication(ObjectNode root) {
    JsonNode login = root.get("loginConfiguration");
    if (login instanceof ObjectNode loginObject) {
      if (!loginObject.hasNonNull("authMethod")) {
        boolean configured =
            text(loginObject, "loginPageUrl") != null
                && text(loginObject, "username") != null
                && text(loginObject, "password") != null;
        loginObject.put("authMethod", configured ? "FORM" : "NONE");
      }
      return;
    }

    JsonNode legacy = root.remove("authentication");
    if (!(legacy instanceof ObjectNode old)) return;
    String username = text(old, "username");
    String password = text(old, "password");
    String loginPageUrl = firstText(old, "loginPageUrl", "loginUrlPattern");
    if (username == null || password == null || loginPageUrl == null) return;

    ObjectNode migrated = mapper.createObjectNode();
    migrated.put("loginPageUrl", loginPageUrl);
    migrated.put("username", username);
    migrated.put("password", password);
    migrated.put("usernameField", valueOr(old, "usernameField", "username"));
    migrated.put("passwordField", valueOr(old, "passwordField", "password"));
    migrated.put(
        "submitSelector",
        valueOr(old, "submitSelector", "button[type='submit'], input[type='submit']"));
    migrated.set(
        "successDetection",
        old.has("successDetection") && !old.get("successDetection").isNull()
            ? old.get("successDetection")
            : mapper.createObjectNode());
    migrated.put("directLogin", false);
    migrated.put("authMethod", "FORM");
    root.set("loginConfiguration", migrated);
  }

  private static String firstText(ObjectNode node, String first, String second) {
    String value = text(node, first);
    return value == null ? text(node, second) : value;
  }

  private static String valueOr(ObjectNode node, String field, String fallback) {
    String value = text(node, field);
    return value == null ? fallback : value;
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) return null;
    return value.asText();
  }
}
