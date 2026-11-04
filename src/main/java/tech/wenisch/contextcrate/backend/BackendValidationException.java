package tech.wenisch.contextcrate.backend;

/** A dedicated storage or search backend was refused: not allowed, invalid, or the connection test failed. */
public class BackendValidationException extends RuntimeException {
  private final String field;
  private final ConnectionTest test;

  /** @param field {@code storage} or {@code searchBackend}; @param test the failed steps, or {@code null} when the settings were refused before testing */
  public BackendValidationException(String field, String message, ConnectionTest test) {
    super(message);
    this.field = field;
    this.test = test;
  }

  public String field() { return field; }
  public ConnectionTest test() { return test; }
}
