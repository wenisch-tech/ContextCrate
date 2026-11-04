package tech.wenisch.contextcrate.index;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Pure helpers for the pgvector backend: identifiers, vector literals and the full-text terms. */
final class PgVectorSql {
  /** HNSW indexes on the {@code vector} type are limited to 2,000 dimensions. */
  static final int MAX_HNSW_DIMENSIONS = 2000;
  /** A tsvector is limited to 1 MB; a document body is reduced to its most frequent distinct terms. */
  static final int MAX_TERMS = 20_000;

  private PgVectorSql() {}

  /** {@code {prefix}_c{crateId without dashes}_{generation}}: one table per crate and index generation. */
  static String table(String prefix, UUID crateId, int generation) {
    return prefix + "_c" + crateId.toString().replace("-", "") + "_" + generation;
  }

  static String tablePrefix(String prefix, UUID crateId) { return prefix + "_c" + crateId.toString().replace("-", "") + "_"; }

  static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }

  /** The text form pgvector accepts for a {@code vector} value: {@code [0.1,0.2]}. */
  static String vector(float[] values) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < values.length; i++) sb.append(i == 0 ? "" : ",").append(values[i]);
    return sb.append(']').toString();
  }

  /**
   * Lexemes for the full-text index: the terms of the shared code-aware analyzer reduced to letters and digits,
   * so PostgreSQL's own parser never splits or reinterprets them (an identifier and its parts stay separate lexemes).
   */
  static List<String> lexemes(String text) {
    if (text == null || text.isBlank()) return List.of();
    Set<String> unique = new LinkedHashSet<>();
    for (String term : SparseVectors.terms(text)) {
      String lexeme = term.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
      if (!lexeme.isEmpty()) unique.add(lexeme);
      if (unique.size() >= MAX_TERMS) break;
    }
    return new ArrayList<>(unique);
  }

  /** Terms separated by a space, ready for {@code to_tsvector('simple', …)}. */
  static String tsvectorText(String text) { return String.join(" ", lexemes(text)); }

  /** An OR query over the lexemes, every one quoted: {@code 'a' | 'b'}. Empty when the text has no usable term. */
  static String tsquery(String text) {
    List<String> terms = lexemes(text);
    StringBuilder sb = new StringBuilder();
    for (String term : terms) sb.append(sb.isEmpty() ? "" : " | ").append('\'').append(term.replace("'", "''").replace("\\", "\\\\")).append('\'');
    return sb.toString();
  }
}
