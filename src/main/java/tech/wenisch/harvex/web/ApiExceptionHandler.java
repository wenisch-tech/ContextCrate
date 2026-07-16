package tech.wenisch.harvex.web;

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

  @ExceptionHandler(java.util.NoSuchElementException.class)
  ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("timestamp", Instant.now().toString(), "error", "Not found"));
  }
}
