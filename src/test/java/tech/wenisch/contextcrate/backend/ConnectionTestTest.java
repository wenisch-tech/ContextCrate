package tech.wenisch.contextcrate.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.backend.ConnectionTest.Status;

class ConnectionTestTest {
  @Test
  void aFailedStepSkipsTheRestAndTheResultIsNotOk() {
    var result = new ConnectionTest.Builder().plan("a", "First").plan("b", "Second").plan("c", "Third")
        .run(() -> "fine").run(() -> { throw new IllegalStateException("boom"); }).run(() -> "never").build();
    assertThat(result.ok()).isFalse();
    assertThat(result.steps()).extracting(ConnectionTest.Step::status).containsExactly(Status.OK, Status.FAILED, Status.SKIPPED);
    assertThat(result.steps().get(0).detail()).isEqualTo("fine");
    assertThat(result.steps().get(1).detail()).isEqualTo("boom");
  }

  @Test
  void plannedStepsThatWereNeverRunAreSkipped() {
    var result = new ConnectionTest.Builder().plan("a", "First").plan("b", "Second").run(() -> null).build();
    assertThat(result.steps()).extracting(ConnectionTest.Step::status).containsExactly(Status.OK, Status.SKIPPED);
  }

  @Test
  void typicalFailuresAreExplainedInPlainWords() {
    assertThat(ConnectionTest.describe(new RuntimeException("wrapped", new UnknownHostException("nope.example")))).contains("cannot be resolved").contains("nope.example");
    assertThat(ConnectionTest.describe(new RuntimeException(new ConnectException("refused")))).contains("Connection refused");
    assertThat(ConnectionTest.describe(new java.net.http.HttpTimeoutException("t"))).contains("did not answer in time");
    assertThat(ConnectionTest.describe(new javax.net.ssl.SSLHandshakeException("PKIX path"))).contains("TLS");
  }
}
