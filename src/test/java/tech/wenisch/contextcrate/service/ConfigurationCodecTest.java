package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;

class ConfigurationCodecTest {
  private final ConfigurationCodec codec = new ConfigurationCodec(new ObjectMapper());

  @Test
  void missingOutputUsesDefaultChunking() {
    CrawlConfiguration config =
        codec.read(
            """
            {"scope":{"seedUrl":"https://example.com"}}
            """);

    assertThat(config.output().chunkSize()).isEqualTo(1000);
    assertThat(config.output().chunkOverlap()).isEqualTo(200);
  }

  @Test
  void semanticChunkingDefaultsToOffAndIsClamped() {
    CrawlConfiguration old = codec.read("""
        {"scope":{"seedUrl":"https://example.com"},"output":{"chunkSize":800}}
        """);
    assertThat(old.output().semantic().enabled()).isFalse();

    CrawlConfiguration configured = codec.read("""
        {"scope":{"seedUrl":"https://example.com"},"output":{"chunkSize":800,
         "semantic":{"enabled":true,"method":"bogus","value":0,"minChars":5000}}}
        """);
    var semantic = configured.output().semantic();
    assertThat(semantic.enabled()).isTrue();
    assertThat(semantic.method()).isEqualTo("percentile");
    assertThat(semantic.value()).isEqualTo(95);
    assertThat(semantic.minChars()).isEqualTo(400);

    var stddev = new CrawlConfiguration.SemanticChunking(true, "STDDEV", 99, 100).normalized(1000);
    assertThat(stddev.method()).isEqualTo("stddev");
    assertThat(stddev.value()).isEqualTo(6);
  }

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
