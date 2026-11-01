package tech.wenisch.contextcrate.service.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownBlocksTest {
  private static final String DOC = """
      # Guide

      Intro with `snake_case` and `List<String>` and **bold**.

      ## Setup

      Run this:

      ```bash
      mvn -q test
      echo "a * b"
      ```

          indented_code();

      | a | b |
      |---|---|
      | 1 | 2 |

      - first
        - nested
      - second
      """;

  @Test
  void fencedCodeKeepsLanguageAndExactText() {
    var blocks = MarkdownBlocks.parse(DOC).blocks();
    Block fence = blocks.stream().filter(b -> "bash".equals(b.language())).findFirst().orElseThrow();
    assertThat(fence.kind()).isEqualTo(Block.Kind.CODE);
    assertThat(fence.text()).isEqualTo("mvn -q test\necho \"a * b\"");
    assertThat(fence.headingPath()).containsExactly("Guide", "Setup");
  }

  @Test
  void inlineCodeIsNotMangled() {
    var intro = MarkdownBlocks.parse(DOC).blocks().get(0);
    assertThat(intro.text()).contains("`snake_case`").contains("`List<String>`")
        .doesNotContain("**");
  }

  @Test
  void indentedCodeTablesAndListsAreRecognised() {
    var blocks = MarkdownBlocks.parse(DOC).blocks();
    assertThat(blocks).anyMatch(b -> b.kind() == Block.Kind.CODE && b.text().equals("indented_code();"));
    assertThat(blocks).anyMatch(b -> b.text().equals("a | b\n1 | 2"));
    assertThat(blocks).anyMatch(b -> b.text().equals("- first\n  - nested\n- second"));
  }

  @Test
  void codeBlockIsNeverCutWhenItFits() {
    var result = MarkdownBlocks.parse(DOC);
    List<ChunkDraft> chunks = BlockPacker.pack(result.blocks(), new ChunkOptions(80, 10));
    assertThat(chunks).anyMatch(c -> c.content().contains("mvn -q test") && c.content().contains("echo \"a * b\""));
  }

  @Test
  void oversizeCodeIsSplitOnLineBoundaries() {
    StringBuilder code = new StringBuilder("```java\n");
    for (int i = 0; i < 40; i++) code.append("int value").append(i).append(" = ").append(i).append(";\n");
    code.append("```\n");
    var chunks = BlockPacker.pack(MarkdownBlocks.parse(code.toString()).blocks(), new ChunkOptions(200, 20));
    assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(c -> {
      assertThat(c.kind()).isEqualTo("code");
      assertThat(c.language()).isEqualTo("java");
      assertThat(c.content()).matches("(?s)(int value\\d+ = \\d+;\\n?)+");
    });
  }

  @Test
  void explanationAndSnippetShareAChunkWithTheFenceKept() {
    var chunks = BlockPacker.pack(MarkdownBlocks.parse(DOC).blocks(), new ChunkOptions(1000, 100));
    ChunkDraft setup = chunks.stream().filter(c -> "Guide > Setup".equals(c.heading())).findFirst().orElseThrow();
    assertThat(setup.content()).contains("Run this:").contains("```bash\nmvn -q test");
  }
}
