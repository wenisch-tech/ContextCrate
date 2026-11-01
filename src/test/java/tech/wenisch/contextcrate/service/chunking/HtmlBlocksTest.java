package tech.wenisch.contextcrate.service.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class HtmlBlocksTest {
  private static final String HTML = """
      <main>
        <h1>Install</h1>
        <p>Use the <code>docker run</code> command.</p>
        <h2>Compose</h2>
        <div class="highlight"><pre><code class="language-yaml">services:
        app:
          image: x
      </code></pre></div>
        <ul><li>one</li><li>two<ul><li>nested</li></ul></li></ul>
        <table><tr><th>Key</th><th>Value</th></tr><tr><td>a</td><td>1</td></tr></table>
      </main>""";

  @Test
  void preKeepsLineBreaksIndentationAndLanguage() {
    var result = HtmlBlocks.parse(Jsoup.parse(HTML).selectFirst("main"));
    Block code = result.blocks().stream().filter(b -> b.kind() == Block.Kind.CODE).findFirst().orElseThrow();
    assertThat(code.language()).isEqualTo("yaml");
    assertThat(code.text()).isEqualTo("services:\n  app:\n    image: x");
    assertThat(code.headingPath()).containsExactly("Install", "Compose");
  }

  @Test
  void headingsListsAndTablesBecomeStructure() {
    var result = HtmlBlocks.parse(Jsoup.parse(HTML).selectFirst("main"));
    assertThat(result.headings()).containsExactly("Install", "Compose");
    assertThat(result.blocks()).anyMatch(b -> b.text().equals("Use the docker run command."));
    assertThat(result.blocks()).anyMatch(b -> b.text().equals("- one\n- two\n  - nested"));
    assertThat(result.blocks()).anyMatch(b -> b.text().equals("Key | Value\na | 1"));
  }
}
