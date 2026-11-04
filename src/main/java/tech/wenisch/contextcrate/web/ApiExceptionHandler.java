package tech.wenisch.contextcrate.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "timestamp",
                Instant.now().toString(),
                "error",
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
  }

  /** A refused or failed dedicated backend: which part, why, and the steps of the connection test. */
  @ExceptionHandler(tech.wenisch.contextcrate.backend.BackendValidationException.class)
  ResponseEntity<Map<String, Object>> backend(tech.wenisch.contextcrate.backend.BackendValidationException e) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("error", e.getMessage());
    body.put("field", e.field());
    if (e.test() != null) body.put("steps", e.test().steps());
    return ResponseEntity.unprocessableEntity().body(body);
  }

  @ExceptionHandler(java.util.NoSuchElementException.class)
  ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("timestamp", Instant.now().toString(), "error", "Not found"));
  }
}
