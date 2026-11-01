package tech.wenisch.contextcrate.service.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CodeChunkerTest {
  private static final String JAVA = """
      package demo;

      import java.util.List;

      /** Looks users up. */
      public class UserService {
        private final Repo repo;

        public User findById(long id) {
          return repo.get(id);
        }

        public List<User> findAll() {
          return repo.all();
        }
      }
      """;

  @Test
  void javaFileFitsInOneChunkButKeepsSymbolAndLanguage() {
    List<ChunkDraft> chunks = CodeChunker.chunk("src/UserService.java", JAVA, new ChunkOptions(2000, 0));
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).kind()).isEqualTo("code");
    assertThat(chunks.get(0).language()).isEqualTo("java");
    assertThat(chunks.get(0).content()).contains("findById").contains("findAll");
  }

  @Test
  void largeClassIsSplitIntoMethodsWithQualifiedSymbols() {
    List<ChunkDraft> chunks = CodeChunker.chunk("UserService.java", JAVA, new ChunkOptions(120, 0));
    assertThat(chunks).extracting(ChunkDraft::symbol).anyMatch(s -> s.contains("UserService.findById"));
    assertThat(chunks).extracting(ChunkDraft::symbol).anyMatch(s -> s.contains("UserService.findAll"));
    assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(200));
    // no method is cut in half
    assertThat(chunks).filteredOn(c -> c.content().contains("findById"))
        .allSatisfy(c -> assertThat(c.content()).contains("return repo.get(id);"));
  }

  @Test
  void pythonFunctionsKeepTheirDocstringsAndComments() {
    String py = "# helper\ndef add(a, b):\n    \"\"\"Add.\"\"\"\n    return a + b\n\n\nclass Box:\n    def open(self):\n        return 1\n";
    List<ChunkDraft> chunks = CodeChunker.chunk("m.py", py, new ChunkOptions(60, 0));
    assertThat(chunks.get(0).content()).startsWith("# helper").contains("def add");
    assertThat(chunks).extracting(ChunkDraft::symbol).anyMatch(s -> s != null && s.startsWith("Box"));
  }

  @Test
  void unknownAndBrokenInputFallsBackToLines() {
    String yaml = "a: 1\n\nb:\n  - x\n  - y\n";
    List<ChunkDraft> chunks = CodeChunker.chunk("conf.yaml", yaml, new ChunkOptions(2000, 0));
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).language()).isEqualTo("yaml");
    List<ChunkDraft> broken = CodeChunker.chunk("Broken.java", "public class {{{ (((", new ChunkOptions(2000, 0));
    assertThat(broken).isNotEmpty();
  }
}
