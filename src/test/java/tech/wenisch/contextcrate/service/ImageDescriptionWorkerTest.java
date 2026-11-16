package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.DocumentImage;

class ImageDescriptionWorkerTest {
  private static byte[] png(int w, int h) throws Exception {
    BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    java.util.Random random = new java.util.Random(1);
    for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) image.setRGB(x, y, random.nextInt());
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  @Test
  void tinyBannerAndSvgImagesAreRejectedAfterDownload() throws Exception {
    assertThat(ImageDescriptionWorker.check(new byte[100], "https://x.io/a.png")).isEqualTo("image smaller than 5 KB");
    assertThat(ImageDescriptionWorker.check(png(60, 60), "https://x.io/a.png")).isIn("image smaller than 5 KB", "shorter side below 128px");
    assertThat(ImageDescriptionWorker.check(png(2000, 100), "https://x.io/a.png")).isEqualTo("shorter side below 128px");
    assertThat(ImageDescriptionWorker.check(png(1800, 200), "https://x.io/a.png")).isEqualTo("banner-shaped image");
    assertThat(ImageDescriptionWorker.check(png(300, 300), "https://x.io/a.svg")).isEqualTo("svg is not described");
  }

  private static DocumentChunk text(int ordinal, String heading, String content) {
    return new DocumentChunk(UUID.randomUUID(), UUID.randomUUID(), ordinal, heading, content, "h");
  }

  private static DocumentImage image(String url, String heading) {
    return new DocumentImage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), url, null, "alt", null, null, heading);
  }

  @Test
  void theCaptionIsAnchoredAtTheChunkThatContainsTheImageReference() {
    var chunks = List.of(text(0, "Guide", "Intro"), text(1, "Guide > Setup", "Setup steps.\n\n[Image: Architecture] (https://x.io/arch.png)"), text(2, "Guide > Setup", "More steps."));

    assertThat(ImageDescriptionWorker.anchorOf(chunks, image("https://x.io/arch.png", "Guide > Setup"))).isEqualTo(1);
  }

  @Test
  void withoutAReferenceTheLastChunkUnderTheHeadingIsUsedAndOtherwiseNone() {
    var chunks = List.of(text(0, "Guide", "Intro"), text(1, "Guide > Setup", "Setup steps."), text(2, "Guide > Setup", "More steps."), text(3, "Other", "Text"));

    assertThat(ImageDescriptionWorker.anchorOf(chunks, image("https://x.io/none.png", "Guide > Setup"))).isEqualTo(2);
    assertThat(ImageDescriptionWorker.anchorOf(chunks, image("https://x.io/none.png", "Missing"))).isNull();
    assertThat(ImageDescriptionWorker.anchorOf(chunks, image("https://x.io/none.png", null))).isNull();
  }

  @Test
  void anImageChunkIsNeverAnAnchor() {
    var picture = text(5, "Guide", "Image: x\n(https://x.io/arch.png)");
    picture.describe("image", null, null, null, null);

    assertThat(ImageDescriptionWorker.anchorOf(List.of(picture), image("https://x.io/arch.png", "Guide"))).isNull();
  }

  @Test
  void aNormalImagePasses() throws Exception {
    assertThat(ImageDescriptionWorker.check(png(400, 300), "https://x.io/diagram.png")).isNull();
  }
}
