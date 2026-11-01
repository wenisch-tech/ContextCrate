package tech.wenisch.contextcrate.service.chunking;

/** Size is in characters. Overlap only applies when long prose has to be cut inside a paragraph. */
public record ChunkOptions(int size, int overlap) {
  public ChunkOptions {
    size = Math.max(50, size);
    overlap = Math.max(0, Math.min(overlap, size - 1));
  }
}
