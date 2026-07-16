package tech.wenisch.harvex.storage;

import java.io.IOException;
import java.io.InputStream;

public interface ArtifactStore {
  ArtifactMetadata put(String key, InputStream content, long maximumBytes) throws IOException;

  InputStream open(String key) throws IOException;

  boolean exists(String key);

  void delete(String key) throws IOException;

  record ArtifactMetadata(String key, String sha256, long length) {}
}
