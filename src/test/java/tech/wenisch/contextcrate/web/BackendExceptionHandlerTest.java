package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tech.wenisch.contextcrate.backend.BackendValidationException;
import tech.wenisch.contextcrate.backend.ConnectionTest;

class BackendExceptionHandlerTest {
  @Test
  void aRefusedBackendBecomesAnUnprocessableResponseNamingTheFieldAndTheFailedSteps() {
    var test = new ConnectionTest(false, java.util.List.of(new ConnectionTest.Step("bucket", "Bucket found", ConnectionTest.Status.FAILED, "missing")));
    var response = new ApiExceptionHandler().backend(new BackendValidationException("storage", "The storage cannot be used", test));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).containsEntry("field", "storage").containsEntry("error", "The storage cannot be used").containsKey("steps");
    var noTest = new ApiExceptionHandler().backend(new BackendValidationException("searchBackend", "not enabled", null));
    assertThat(noTest.getBody()).doesNotContainKey("steps");
  }
}
