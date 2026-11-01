package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;

class CodeAwareAnalyzerTest {
  private static List<String> tokens(String text) throws IOException {
    List<String> out = new ArrayList<>();
    try (var analyzer = new CodeAwareAnalyzer(); var stream = analyzer.tokenStream("text", text)) {
      var term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) out.add(term.toString());
      stream.end();
    }
    return out;
  }

  @Test
  void splitsCamelAndSnakeCaseButKeepsTheOriginal() throws IOException {
    assertThat(tokens("getUserName")).contains("getusername", "get", "user", "name");
    assertThat(tokens("snake_case_name")).contains("snake_case_name", "snake", "case", "name");
    assertThat(tokens("HTTP2Client")).contains("http", "client");
  }
}
