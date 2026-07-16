package tech.wenisch.harvex.storage;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.config.HarvexProperties;

@Component
@ConditionalOnProperty(
    name = "harvex.artifacts.backend",
    havingValue = "filesystem",
    matchIfMissing = true)
public class FileSystemArtifactStore implements ArtifactStore {
  private final Path root;

  public FileSystemArtifactStore(HarvexProperties properties) throws IOException {
    root = properties.artifacts().path().toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  @Override
  public ArtifactMetadata put(String key, InputStream content, long max) throws IOException {
    Path target = resolve(key);
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), ".harvex-", ".tmp");
    long length = 0;
    MessageDigest digest = digest();
    try (var out = Files.newOutputStream(temp)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = content.read(buffer)) >= 0) {
        length += read;
        if (length > max)
          throw new IOException("Artifact exceeds configured maximum of " + max + " bytes");
        digest.update(buffer, 0, read);
        out.write(buffer, 0, read);
      }
    } catch (Exception e) {
      Files.deleteIfExists(temp);
      throw e;
    }
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return new ArtifactMetadata(key, java.util.HexFormat.of().formatHex(digest.digest()), length);
  }

  @Override
  public InputStream open(String key) throws IOException {
    return Files.newInputStream(resolve(key));
  }

  @Override
  public boolean exists(String key) {
    return Files.isRegularFile(resolve(key));
  }

  @Override
  public void delete(String key) throws IOException {
    Files.deleteIfExists(resolve(key));
  }

  private Path resolve(String key) {
    Path p = root.resolve(key.replace('\\', '/')).normalize();
    if (!p.startsWith(root))
      throw new IllegalArgumentException("Artifact key escapes configured root");
    return p;
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
