package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.reranking.DisabledRerankingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;

/** The shared expectations against a real PostgreSQL with pgvector; see {@link PgVectorLiveTest}. */
abstract class PgVectorSearchIndexContract extends SearchIndexContract {
  abstract String url();
  abstract String user();
  abstract String password();

  /** Tables of different tests share one database, so each test works under its own prefix. */
  final String prefix = "cc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

  DataSource dataSource() { return new DriverManagerDataSource(url(), user(), password()); }

  @Override
  SearchIndex index(Topics embeddings) {
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(new Crate(crateId, "pgvector", null, null)));
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"), null,
        new ContextCrateProperties.Index("pgvector", Path.of("unused"), url(), prefix, null, user(), password(), false), null, null);
    return new PgVectorSearchIndex(properties, dataSource(), embeddings, new DisabledRerankingProvider(), crates);
  }

  List<String> ownTables() {
    return new JdbcTemplate(dataSource()).queryForList("select tablename from pg_tables where schemaname = current_schema() and starts_with(tablename, ?)", String.class, prefix + "_c");
  }

  @Override int ownIndexCount() { return ownTables().size(); }

  @Test
  void createsAVectorColumnAnHnswIndexAndAFullTextIndex() throws Exception {
    var doc = document("Handbook", "body");
    index(new Topics(true)).upsert(doc, List.of(ChunkRetrievalRecord.standard(chunk(doc, 0, "Customers receive an invoice by email."))));

    String table = ownTables().getFirst();
    var jdbc = new JdbcTemplate(dataSource());
    assertThat(jdbc.queryForObject("select format_type(atttypid, atttypmod) from pg_attribute where attrelid = ?::regclass and attname = 'embedding'", String.class, "\"" + table + "\""))
        .isEqualTo("vector(3)");
    List<String> indexes = jdbc.queryForList("select indexdef from pg_indexes where tablename = ?", String.class, table);
    assertThat(indexes).anyMatch(d -> d.contains("USING hnsw") && d.contains("vector_cosine_ops"));
    assertThat(indexes).anyMatch(d -> d.contains("USING gin") && d.contains("tsv"));
  }

  @Test
  void identifiersAreFoundByTheirPartsAndHostileQueriesAreHarmless() throws Exception {
    var doc = document("Handbook", "body");
    var code = chunk(doc, 0, "Call getUserName before rendering the profile page.");
    var index = index(new Topics(false));
    index.upsert(doc, List.of(ChunkRetrievalRecord.standard(code)));

    assertThat(index.search(new SearchIndex.SearchRequest(crateId, "user", 10, null, "chunk", "lexical")).hits()).extracting(SearchIndex.SearchHit::id).containsExactly(code.getId());
    assertThat(index.search(new SearchIndex.SearchRequest(crateId, "getUserName", 10, null, "chunk", "lexical")).hits()).hasSize(1);
    for (String hostile : List.of("a' | 'b & !c :* \\ (x)", "'; drop table x; --", "&|!", "\"quoted\""))
      assertThat(index.search(new SearchIndex.SearchRequest(crateId, hostile, 10, null, "chunk", "lexical")).hits()).isNotNull();
  }

  @Test
  void aPrefixThatIsNotAPlainIdentifierIsRejected() {
    CrateRepository crates = mock(CrateRepository.class);
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"), null,
        new ContextCrateProperties.Index("pgvector", Path.of("unused"), url(), "Bad-Prefix", null, user(), password(), false), null, null);
    assertThatThrownBy(() -> new PgVectorSearchIndex(properties, dataSource(), new Topics(true), new DisabledRerankingProvider(), crates)).isInstanceOf(IllegalStateException.class);
  }
}
