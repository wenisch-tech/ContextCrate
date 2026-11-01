package tech.wenisch.contextcrate.service.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class ImageFilterTest {
  private static final ImageFilter.Options ON = ImageFilter.Options.of(true, 64, "");

  private static ImageRef img(String url, String alt, Integer w, Integer h, String hints) {
    return new ImageRef(url, alt, null, null, w, h, hints, false);
  }

  @Test
  void decorativeAndSmallImagesAreRejected() {
    assertThat(ImageFilter.reject(img("https://x.io/a.png", "", null, null, ""), ON)).isEqualTo("empty alt text");
    assertThat(ImageFilter.reject(img("https://x.io/a.png", "chart", 16, 16, ""), ON)).contains("smaller");
    assertThat(ImageFilter.reject(new ImageRef("https://x.io/a.png", "x", null, null, null, null, "", true), ON)).isEqualTo("marked decorative");
    assertThat(ImageFilter.reject(img("https://x.io/favicon.ico", "site", null, null, ""), ON)).isEqualTo("icon file");
  }

  @Test
  void iconsLogosBadgesAndTrackersAreRejected() {
    assertThat(ImageFilter.reject(img("https://x.io/img/logo.png", "Acme", null, null, ""), ON)).contains("decoration");
    assertThat(ImageFilter.reject(img("https://x.io/a.png", "Acme", null, null, "social-icon"), ON)).contains("decoration");
    assertThat(ImageFilter.reject(img("https://img.shields.io/badge/build-passing.svg", "build", null, null, ""), ON)).isEqualTo("badge or tracker");
    assertThat(ImageFilter.reject(img("data:image/gif;base64,R0lGOD", "pixel", null, null, ""), ON)).isEqualTo("tiny inline image");
  }

  @Test
  void aRealCaptionOrDescriptiveAltBeatsANameHint() {
    assertThat(ImageFilter.reject(img("https://x.io/logo-architecture.png", "Architecture of the ingestion pipeline", null, null, ""), ON)).isNull();
    assertThat(ImageFilter.reject(img("https://x.io/diagrams/flow.png", "Request flow", 800, 400, ""), ON)).isNull();
  }

  @Test
  void extraPatternsAreApplied() {
    var options = ImageFilter.Options.of(true, 64, "screenshot-ad, /thumbs/");
    assertThat(ImageFilter.reject(img("https://x.io/thumbs/a.png", "photo of the team", null, null, ""), options)).contains("skip pattern");
  }

  @Test
  void applyDropsRejectedDuplicatesAndResolvesUrls() {
    List<Block> blocks = List.of(
        Block.prose(List.of(), "Intro"),
        Block.image(List.of(), img("a.png", "First diagram", null, null, "")),
        Block.image(List.of(), img("a.png", "First diagram", null, null, "")),
        Block.image(List.of(), img("logo.png", "Acme", null, null, "")));
    var outcome = ImageFilter.apply(blocks, ON, url -> "https://x.io/docs/" + url);
    assertThat(outcome.kept()).hasSize(1);
    assertThat(outcome.kept().get(0).url()).isEqualTo("https://x.io/docs/a.png");
    assertThat(outcome.skipped()).extracting(ImageFilter.Skipped::reason).anyMatch(r -> r.contains("duplicate")).anyMatch(r -> r.contains("decoration"));
    assertThat(outcome.blocks()).hasSize(2);
  }

  @Test
  void disabledOptionsRemoveAllImages() {
    var outcome = ImageFilter.apply(List.of(Block.prose(List.of(), "t"),
        Block.image(List.of(), img("https://x.io/d.png", "Diagram of x", null, null, ""))), ImageFilter.Options.disabled(), null);
    assertThat(outcome.blocks()).hasSize(1);
    assertThat(outcome.kept()).isEmpty();
  }

  @Test
  void htmlFiguresPicturesAndLinkedImagesAreRead() {
    var root = Jsoup.parse("""
        <main><h1>Docs</h1>
        <p>See below.</p>
        <figure><img src="/img/arch.png" alt="Architecture" width="800"><figcaption>The overall architecture</figcaption></figure>
        <a href="/img/big.png"><img src="/img/thumb.png" alt="Dashboard screenshot"></a>
        <picture><source srcset="/img/s-1x.png 1x, /img/s-2x.png 2x"><img src="/img/s.png" alt="Responsive"></picture>
        <img src="/img/logo.svg" alt="" class="logo">
        <svg><title>Inline chart</title><desc>Sales per month</desc></svg>
        </main>""", "https://docs.example.com/guide/").selectFirst("main");
    var refs = HtmlBlocks.parse(root).blocks().stream().filter(b -> b.kind() == Block.Kind.IMAGE).map(Block::image).toList();
    assertThat(refs).hasSize(5);
    assertThat(refs.get(0).url()).isEqualTo("https://docs.example.com/img/arch.png");
    assertThat(refs.get(0).caption()).isEqualTo("The overall architecture");
    assertThat(refs.get(1).url()).isEqualTo("https://docs.example.com/img/big.png");
    assertThat(refs.get(2).url()).isEqualTo("https://docs.example.com/img/s-2x.png");
    assertThat(refs.get(4).url()).isNull();
    assertThat(refs.get(4).alt()).isEqualTo("Inline chart");
    var outcome = ImageFilter.apply(HtmlBlocks.parse(root).blocks(), ON, u -> u);
    assertThat(outcome.kept()).hasSize(4); // the empty-alt logo is skipped
  }

  @Test
  void markdownImagesBecomeImageBlocks() {
    var blocks = MarkdownBlocks.parse("# T\n\nText ![Pipeline overview](img/pipeline.png \"Overview\") end.\n").blocks();
    assertThat(blocks).anyMatch(b -> b.kind() == Block.Kind.IMAGE && "Pipeline overview".equals(b.image().alt()));
    var chunks = BlockPacker.pack(blocks, new ChunkOptions(1000, 0));
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).content()).contains("[Image: Pipeline overview").contains("img/pipeline.png");
  }
}
