package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PgVectorSqlTest {
  @Test
  void tableNamesAreStablePlainIdentifiersWithinPostgresLimits() {
    UUID crate = UUID.fromString("00000000-0000-0000-0000-000000000001");
    assertThat(PgVectorSql.table("contextcrate", crate, 3)).isEqualTo("contextcrate_c00000000000000000000000000000001_3");
    assertThat(PgVectorSql.table("abcdefghijklmnopqrst", crate, 123456).length()).isLessThanOrEqualTo(63);
    assertThat(PgVectorSql.tablePrefix("contextcrate", crate)).isEqualTo("contextcrate_c00000000000000000000000000000001_");
    assertThat(PgVectorSql.quote("a\"b")).isEqualTo("\"a\"\"b\"");
  }

  @Test
  void vectorsUseTheTextFormOfPgvector() {
    assertThat(PgVectorSql.vector(new float[] {1f, 0.5f, -0.25f})).isEqualTo("[1.0,0.5,-0.25]");
    assertThat(PgVectorSql.vector(new float[0])).isEqualTo("[]");
  }

  @Test
  void lexemesKeepIdentifiersAndTheirPartsAsSeparateWords() {
    assertThat(PgVectorSql.lexemes("getUserName")).contains("getusername", "get", "user", "name");
    assertThat(PgVectorSql.lexemes("snake_case_name")).contains("snakecasename", "snake", "case", "name");
    assertThat(PgVectorSql.lexemes("  ")).isEmpty();
    assertThat(PgVectorSql.lexemes("Müller & Söhne")).contains("müller", "söhne");
    assertThat(PgVectorSql.tsvectorText("pager pager monday")).isEqualTo("pager monday");
  }

  @Test
  void theQueryIsAnOrOfQuotedLexemesAndNeverContainsOperatorCharacters() {
    assertThat(PgVectorSql.tsquery("pager monday")).isEqualTo("'pager' | 'monday'");
    String hostile = PgVectorSql.tsquery("a' | 'b & !c :* \\ (x)");
    assertThat(hostile).doesNotContain("&", "!", ":*", "\\", "(", ")");
    assertThat(hostile).matches("('[\\p{L}\\p{N}]+'( \\| )?)+");
    assertThat(PgVectorSql.tsquery("&&& !!!")).isEmpty();
  }
}
