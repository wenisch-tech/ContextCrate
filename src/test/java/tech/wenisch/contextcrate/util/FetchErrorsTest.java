package tech.wenisch.contextcrate.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class FetchErrorsTest {
  @Test
  void tlsFatalAlertIsExplainedAsRemoteProblem() {
    var message =
        FetchErrors.explain(
            new RuntimeException(new SSLHandshakeException("Received fatal alert: internal_error")));
    assertThat(message).contains("rejected the secure connection").contains("not an issue with ContextCrate");
    assertThat(message).contains("Received fatal alert: internal_error");
  }

  @Test
  void unknownHostAndUnknownErrors() {
    assertThat(FetchErrors.explain(new UnknownHostException("x.invalid"))).contains("could not be resolved");
    assertThat(FetchErrors.explain(new IllegalStateException("boom"))).isEqualTo("boom");
  }
}
