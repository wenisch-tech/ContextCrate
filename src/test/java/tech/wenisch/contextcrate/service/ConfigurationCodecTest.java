package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;

class ConfigurationCodecTest {
  private final ConfigurationCodec codec = new ConfigurationCodec(new ObjectMapper());

  @Test
  void oldConfigurationWithoutAuthenticationDefaultsToNone() {
    CrawlConfiguration config =
        codec.read(
            """
            {
              "scope":{"seedUrl":"https://example.com"},
              "politeness":{},
              "reliability":{},
              "output":{}
            }
            """);
    assertThat(config.loginConfiguration().authMethod())
        .isEqualTo(CrawlConfiguration.AuthMethod.NONE);
  }

  @Test
  void legacyAuthenticationObjectMigratesToFormMode() {
    CrawlConfiguration config =
        codec.read(
            """
            {
              "scope":{"seedUrl":"https://example.com"},
              "authentication":{
                "username":"alice",
                "password":"secret",
                "loginUrlPattern":"https://example.com/login"
              }
            }
            """);
    assertThat(config.loginConfiguration().authMethod())
        .isEqualTo(CrawlConfiguration.AuthMethod.FORM);
    assertThat(config.loginConfiguration().loginPageUrl())
        .isEqualTo("https://example.com/login");
    assertThat(config.loginConfiguration().password()).isEqualTo("secret");
  }

  @Test
  void loginConfigurationWithoutMethodIsInferred() {
    CrawlConfiguration config =
        codec.read(
            """
            {
              "scope":{"seedUrl":"https://example.com"},
              "loginConfiguration":{
                "username":"alice",
                "password":"secret",
                "loginPageUrl":"https://example.com/login"
              }
            }
            """);
    assertThat(config.loginConfiguration().authMethod())
        .isEqualTo(CrawlConfiguration.AuthMethod.FORM);
  }
}
