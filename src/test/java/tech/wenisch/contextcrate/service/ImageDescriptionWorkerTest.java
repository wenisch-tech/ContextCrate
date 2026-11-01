package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

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

  @Test
  void aNormalImagePasses() throws Exception {
    assertThat(ImageDescriptionWorker.check(png(400, 300), "https://x.io/diagram.png")).isNull();
  }
}
