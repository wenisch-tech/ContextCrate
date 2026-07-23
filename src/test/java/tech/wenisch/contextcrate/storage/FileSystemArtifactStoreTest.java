package tech.wenisch.contextcrate.storage;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.contextcrate.config.ContextCrateProperties;

class FileSystemArtifactStoreTest {
  @TempDir Path temp;

  @Test
  void roundTripsAndProtectsRoot() throws Exception {
    var p =
        new ContextCrateProperties(
            "standalone",
            "all",
            null,
            null,
            new ContextCrateProperties.Artifacts("filesystem", temp, null, null, null, null),
            null,
            null,
            null);
    var store = new FileSystemArtifactStore(p);
    var saved = store.put("run/page.html", new ByteArrayInputStream("hello".getBytes()), 100);
    assertThat(saved.length()).isEqualTo(5);
    assertThat(new String(store.open(saved.key()).readAllBytes())).isEqualTo("hello");
    assertThatThrownBy(() -> store.put("../escape", new ByteArrayInputStream(new byte[0]), 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOversizedContent() throws Exception {
    var p =
        new ContextCrateProperties(
            "standalone",
            "all",
            null,
            null,
            new ContextCrateProperties.Artifacts("filesystem", temp, null, null, null, null),
            null,
            null,
            null);
    var store = new FileSystemArtifactStore(p);
    assertThatThrownBy(() -> store.put("large", new ByteArrayInputStream(new byte[20]), 10))
        .isInstanceOf(IOException.class);
  }
}
