package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.DocumentImage;
import tech.wenisch.contextcrate.storage.ArtifactStore;

class ImageBytesTest {
  private final ArtifactStore artifacts = mock(ArtifactStore.class);
  private final UrlPolicy urls = mock(UrlPolicy.class);
  private final ImageBytes imageBytes = new ImageBytes(artifacts, urls);

  private DocumentImage image(String key, String url) {
    var image = new DocumentImage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), url, null, null, null, null, null);
    if (key != null) image.queue(key);
    return image;
  }

  @Test
  void aStoredImageIsReadFromTheArtifactStoreWithoutAnyDownload() throws Exception {
    when(artifacts.open("crates/x/img.img")).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

    assertThat(imageBytes.load(image("crates/x/img.img", "https://example.test/a.png"), 100)).containsExactly(1, 2, 3);
    verifyNoInteractions(urls);
  }

  @Test
  void anImageLargerThanTheLimitIsRejected() throws Exception {
    when(artifacts.open("k")).thenReturn(new ByteArrayInputStream(new byte[50]));

    assertThatThrownBy(() -> imageBytes.load(image("k", "https://example.test/a.png"), 10)).hasMessageContaining("larger than 10");
  }

  @Test
  void anImageWithoutAStoredCopyOrAnAddressCannotBeLoaded() {
    assertThatThrownBy(() -> imageBytes.load(image(null, null), 10)).hasMessageContaining("no address");
  }

  @Test
  void anUnsafeAddressIsRefusedByTheUrlPolicyBeforeAnyRequest() throws Exception {
    doThrow(new IllegalArgumentException("blocked address")).when(urls).assertSafe("http://169.254.169.254/latest");

    assertThatThrownBy(() -> imageBytes.load(image(null, "http://169.254.169.254/latest"), 10)).hasMessageContaining("blocked address");
  }

  @Test
  void theTypeIsRecognisedFromTheFileSignature() {
    assertThat(ImageBytes.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0})).isEqualTo("image/png");
    assertThat(ImageBytes.sniff(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})).isEqualTo("image/jpeg");
    assertThat(ImageBytes.sniff(new byte[] {'G', 'I', 'F', '8'})).isEqualTo("image/gif");
    assertThat(ImageBytes.sniff(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'})).isEqualTo("image/webp");
    assertThat(ImageBytes.sniff("<svg/>".getBytes())).isNull();
    assertThat(ImageBytes.mimeOf("<svg/>".getBytes())).isEqualTo("image/png"); // the description request is lenient
  }
}
