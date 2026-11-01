package tech.wenisch.contextcrate.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * Standard tokenisation plus identifier splitting, so {@code getUserName} is found by "user" or
 * "name", {@code snake_case_name} by "snake" or "name", and {@code HTTP2Client} by "http". The
 * original token is kept, so searching for the exact identifier still ranks best.
 */
public final class CodeAwareAnalyzer extends Analyzer {
  static final int FLAGS =
      WordDelimiterGraphFilter.GENERATE_WORD_PARTS
          | WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
          | WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE
          | WordDelimiterGraphFilter.SPLIT_ON_NUMERICS
          | WordDelimiterGraphFilter.PRESERVE_ORIGINAL;

  @Override
  protected TokenStreamComponents createComponents(String fieldName) {
    StandardTokenizer source = new StandardTokenizer();
    TokenStream stream = new WordDelimiterGraphFilter(source, FLAGS, null);
    stream = new LowerCaseFilter(stream);
    return new TokenStreamComponents(source, stream);
  }

  @Override
  protected TokenStream normalize(String fieldName, TokenStream in) {
    return new LowerCaseFilter(in);
  }
}
