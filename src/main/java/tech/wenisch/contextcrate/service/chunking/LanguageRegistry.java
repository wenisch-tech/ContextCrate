package tech.wenisch.contextcrate.service.chunking;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.treesitter.*;

/**
 * Maps file names to a language label and, for the languages with a bundled tree-sitter grammar,
 * to that grammar. Other languages are still labelled so they are searchable by language, and are
 * split on blank lines instead.
 */
public final class LanguageRegistry {
  private static final Map<String, String> EXTENSIONS = Map.ofEntries(
      Map.entry("java", "java"), Map.entry("py", "python"), Map.entry("pyw", "python"),
      Map.entry("js", "javascript"), Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"),
      Map.entry("jsx", "javascript"), Map.entry("ts", "typescript"), Map.entry("mts", "typescript"),
      Map.entry("cts", "typescript"), Map.entry("go", "go"), Map.entry("rs", "rust"),
      Map.entry("c", "c"), Map.entry("h", "c"), Map.entry("cc", "cpp"), Map.entry("cpp", "cpp"),
      Map.entry("cxx", "cpp"), Map.entry("hpp", "cpp"), Map.entry("hh", "cpp"),
      Map.entry("hxx", "cpp"), Map.entry("cs", "csharp"), Map.entry("rb", "ruby"),
      Map.entry("sh", "bash"), Map.entry("bash", "bash"), Map.entry("zsh", "bash"),
      // labelled only: split on blank lines
      Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"), Map.entry("php", "php"),
      Map.entry("swift", "swift"), Map.entry("scala", "scala"), Map.entry("sql", "sql"),
      Map.entry("yml", "yaml"), Map.entry("yaml", "yaml"), Map.entry("json", "json"),
      Map.entry("toml", "toml"), Map.entry("xml", "xml"), Map.entry("html", "html"),
      Map.entry("htm", "html"), Map.entry("css", "css"), Map.entry("scss", "scss"),
      Map.entry("gradle", "groovy"), Map.entry("groovy", "groovy"), Map.entry("lua", "lua"),
      Map.entry("pl", "perl"), Map.entry("r", "r"), Map.entry("dart", "dart"),
      Map.entry("tf", "terraform"), Map.entry("proto", "protobuf"), Map.entry("ini", "ini"),
      Map.entry("properties", "properties"), Map.entry("rst", "rst"), Map.entry("adoc", "asciidoc"));

  private static final Map<String, Supplier<TSLanguage>> GRAMMARS = Map.ofEntries(
      Map.entry("java", TreeSitterJava::new), Map.entry("python", TreeSitterPython::new),
      Map.entry("javascript", TreeSitterJavascript::new),
      Map.entry("typescript", TreeSitterTypescript::new), Map.entry("go", TreeSitterGo::new),
      Map.entry("rust", TreeSitterRust::new), Map.entry("c", TreeSitterC::new),
      Map.entry("cpp", TreeSitterCpp::new), Map.entry("csharp", TreeSitterCSharp::new),
      Map.entry("ruby", TreeSitterRuby::new), Map.entry("bash", TreeSitterBash::new));

  private LanguageRegistry() {}

  /** Language label for a path, or null when the file is not recognisably source code. */
  public static String languageOf(String path) {
    if (path == null) return null;
    String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    if (name.equals("dockerfile") || name.startsWith("dockerfile.")) return "dockerfile";
    if (name.equals("makefile")) return "makefile";
    int dot = name.lastIndexOf('.');
    return dot < 0 ? null : EXTENSIONS.get(name.substring(dot + 1));
  }

  static boolean hasGrammar(String language) {
    return language != null && GRAMMARS.containsKey(language);
  }

  static TSLanguage grammar(String language) {
    return GRAMMARS.get(language).get();
  }
}
