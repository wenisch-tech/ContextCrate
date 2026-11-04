package tech.wenisch.contextcrate.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.CrateBackendSettings;
import tech.wenisch.contextcrate.repository.CrateBackendSettingsRepository;

class RoutingArtifactStoreTest {
  /** Remembers what was asked of it. */
  static class Recording implements ArtifactStore {
    final List<String> puts = new ArrayList<>(), deletedPrefixes = new ArrayList<>();
    @Override public ArtifactMetadata put(String key, InputStream content, long max) { puts.add(key); return new ArtifactMetadata(key, "sha", 0); }
    @Override public InputStream open(String key) { return new ByteArrayInputStream(new byte[0]); }
    @Override public boolean exists(String key) { return true; }
    @Override public void delete(String key) {}
    @Override public void deletePrefix(String prefix) { deletedPrefixes.add(prefix); }
  }

  final UUID custom = UUID.randomUUID(), plain = UUID.randomUUID();
  final CrateBackendSettingsRepository repository = mock(CrateBackendSettingsRepository.class);
  final Recording global = new Recording();
  final RoutingArtifactStore store = new RoutingArtifactStore(List.of(global), repository);

  private CrateBackendSettings s3Row(UUID crate, String bucket) {
    return new CrateBackendSettings(crate).s3(new CrateBackendSettings.S3Config("http://s3.example.test:9000", "eu-west-1", bucket, "docs", "key", "secret", true));
  }

  @Test
  void keysOfCratesWithoutDedicatedStorageAndOtherKeysGoToTheGlobalStore() throws IOException {
    when(repository.findById(plain)).thenReturn(Optional.empty());
    store.put("crates/" + plain + "/runs/1/page.html", new ByteArrayInputStream(new byte[0]), 10);
    store.put("something/else", new ByteArrayInputStream(new byte[0]), 10);
    assertThat(global.puts).containsExactly("crates/" + plain + "/runs/1/page.html", "something/else");
  }

  @Test
  void aCrateWithDedicatedStorageGetsItsOwnS3StoreAndNothingReachesTheGlobalOne() {
    when(repository.findById(custom)).thenReturn(Optional.of(s3Row(custom, "bucket-a")));
    ArtifactStore resolved = store.storeFor("crates/" + custom + "/runs/1/page.html");
    assertThat(resolved).isInstanceOf(S3ArtifactStore.class).isNotSameAs(global);
    assertThat(store.storeFor("crates/" + custom + "/other")).isSameAs(resolved); // cached
    assertThat(global.puts).isEmpty();
  }

  @Test
  void aRowWithGlobalStorageOnlyUsesTheGlobalStore() {
    var row = new CrateBackendSettings(custom).index(new CrateBackendSettings.IndexConfig("qdrant", "http://q:6333", null, null, null, null, false));
    when(repository.findById(custom)).thenReturn(Optional.of(row));
    assertThat(store.storeFor("crates/" + custom + "/x")).isSameAs(global);
  }

  @Test
  void purgingACratePrefixIsRoutedLikeItsKeys() throws IOException {
    when(repository.findById(plain)).thenReturn(Optional.empty());
    store.deletePrefix("crates/" + plain);
    assertThat(global.deletedPrefixes).containsExactly("crates/" + plain);
    when(repository.findById(custom)).thenReturn(Optional.of(s3Row(custom, "b")));
    assertThat(store.storeFor("crates/" + custom)).isInstanceOf(S3ArtifactStore.class);
  }

  @Test
  void invalidatingForgetsTheCachedStore() {
    when(repository.findById(custom)).thenReturn(Optional.of(s3Row(custom, "bucket-a")));
    ArtifactStore first = store.storeFor("crates/" + custom + "/a");
    store.invalidate(custom);
    when(repository.findById(custom)).thenReturn(Optional.of(s3Row(custom, "bucket-b")));
    assertThat(store.storeFor("crates/" + custom + "/a")).isNotSameAs(first);
  }

  @Test
  void secretsNeverAppearInToStringOutput() {
    var config = new CrateBackendSettings.S3Config("http://x", "r", "b", "p", "AKIA", "super-secret", true);
    assertThat(config.toString()).doesNotContain("super-secret").doesNotContain("AKIA");
    var index = new CrateBackendSettings.IndexConfig("qdrant", "http://x", "api-secret", "user", "pw-secret", "p", false);
    assertThat(index.toString()).doesNotContain("api-secret").doesNotContain("pw-secret");
  }
}
