package tech.wenisch.contextcrate.backend;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.crawl.UrlPolicy;

class EndpointGuardTest {
  private final EndpointGuard strict = new EndpointGuard(new UrlPolicy(false));
  private final EndpointGuard lenient = new EndpointGuard(new UrlPolicy(true));

  @Test
  void privateLoopbackAndMetadataAddressesAreRefusedUnlessAllowed() {
    for (String blocked : new String[] {"http://127.0.0.1:9200", "http://localhost:6333", "https://10.0.0.5", "http://192.168.1.10:9000", "http://169.254.169.254/latest", "http://[::1]:9200"})
      assertThatThrownBy(() -> strict.check(blocked, false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Private network");
    assertThatCode(() -> lenient.check("http://127.0.0.1:9200", false)).doesNotThrowAnyException();
  }

  @Test
  void jdbcUrlsAreCheckedByTheirHost() {
    assertThatThrownBy(() -> strict.check("jdbc:postgresql://127.0.0.1:5432/idx", true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Private network");
    assertThatThrownBy(() -> strict.check("jdbc:postgresql://10.1.2.3/idx", true)).isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> lenient.check("jdbc:postgresql://127.0.0.1:5432/idx?sslmode=require", true)).doesNotThrowAnyException();
  }

  @Test
  void malformedEndpointsGetAnActionableMessage() {
    assertThatThrownBy(() -> strict.check("", false)).hasMessageContaining("required");
    assertThatThrownBy(() -> strict.check("ftp://host", false)).hasMessageContaining("http://");
    assertThatThrownBy(() -> strict.check("http://user:secret@host:9200", false)).hasMessageContaining("credential");
    assertThatThrownBy(() -> strict.check("http://host:5432/db", true)).hasMessageContaining("JDBC");
    assertThatThrownBy(() -> strict.check("jdbc:mysql://host/db", true)).hasMessageContaining("JDBC");
    assertThatThrownBy(() -> strict.check("http://this-host-does-not-exist.invalid", false)).hasMessageContaining("cannot be resolved");
  }
}
