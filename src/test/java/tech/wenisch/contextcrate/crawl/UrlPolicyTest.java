package tech.wenisch.contextcrate.crawl;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;

class UrlPolicyTest {
  private final UrlPolicy policy = new UrlPolicy(false);

  @Test
  void canonicalizesUrls() {
    assertThat(policy.canonicalize("HTTPS://Example.COM:443/a/../b?q=1#fragment"))
        .isEqualTo("https://example.com/b?q=1");
  }

  @Test
  void enforcesHostScope() {
    var scope =
        new CrawlConfiguration.Scope(
            "https://example.com",
            Set.of("example.com"),
            List.of(),
            List.of("*/private/*"),
            3,
            100,
            false,
            false);
    assertThat(policy.inScope("https://example.com/docs", scope)).isTrue();
    assertThat(policy.inScope("https://sub.example.com/docs", scope)).isFalse();
    assertThat(policy.inScope("https://evil.example/docs", scope)).isFalse();
    assertThat(policy.inScope("https://example.com/private/a", scope)).isFalse();
  }

  @Test
  void blocksLoopback() {
    assertThatThrownBy(() -> policy.assertSafe("http://127.0.0.1/test"))
        .isInstanceOf(SecurityException.class);
  }
}
