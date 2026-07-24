package tech.wenisch.contextcrate.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RobotsServiceTest {
  @Test
  void usesLongestAllowRule() {
    var rules =
        RobotsService.parse(
            "User-agent: *\nDisallow: /private\nAllow: /private/public\n", "ContextCrateBot");
    assertThat(rules.allowed("/docs")).isTrue();
    assertThat(rules.allowed("/private/item")).isFalse();
    assertThat(rules.allowed("/private/public/page")).isTrue();
  }
}
