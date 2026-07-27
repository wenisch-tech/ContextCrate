package tech.wenisch.contextcrate.embedding;

/** A provider-neutral signal that an embedding endpoint rejected an input for its size. */
public class EmbeddingInputTooLargeException extends Exception {
  private final int suggestedMaximumCharacters;

  public EmbeddingInputTooLargeException(int suggestedMaximumCharacters) {
    super("Embedding input exceeds the endpoint context limit");
    this.suggestedMaximumCharacters = suggestedMaximumCharacters;
  }

  public int suggestedMaximumCharacters() { return suggestedMaximumCharacters; }
}
