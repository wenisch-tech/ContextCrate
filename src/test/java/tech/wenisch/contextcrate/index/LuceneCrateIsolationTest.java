package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.embedding.DisabledEmbeddingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;

class LuceneCrateIsolationTest {
  @TempDir Path temporary;

  @Test
  void physicallySeparatesSearchNamespaces() throws Exception {
    UUID first=UUID.randomUUID(),second=UUID.randomUUID();
    CrateRepository crates=mock(CrateRepository.class);
    when(crates.findById(first)).thenReturn(Optional.of(new Crate(first,"First",null,null)));
    when(crates.findById(second)).thenReturn(Optional.of(new Crate(second,"Second",null,null)));
    var properties=new ContextCrateProperties("standalone","all",
        new ContextCrateProperties.Queue("local"),new ContextCrateProperties.Database("h2"),
        null,new ContextCrateProperties.Index("lucene",temporary,null,"contextcrate"),null,null);
    try(var index=new LuceneSearchIndex(properties,new DisabledEmbeddingProvider(),crates)){
      index.upsert(document(first,"alpha shared phrase"),List.of());
      index.upsert(document(second,"beta shared phrase"),List.of());
      var firstResults=index.search(new SearchIndex.SearchRequest(first,"shared",10,null,"document","lexical"));
      var secondResults=index.search(new SearchIndex.SearchRequest(second,"shared",10,null,"document","lexical"));
      assertThat(firstResults.hits()).singleElement().satisfies(h->assertThat(h.title()).startsWith("alpha"));
      assertThat(secondResults.hits()).singleElement().satisfies(h->assertThat(h.title()).startsWith("beta"));
      assertThat(Files.isDirectory(temporary.resolve("crates").resolve(first.toString()).resolve("1"))).isTrue();
      assertThat(Files.isDirectory(temporary.resolve("crates").resolve(second.toString()).resolve("1"))).isTrue();
    }
  }

  private static NormalizedDocument document(UUID crateId,String body){
    var value=new NormalizedDocument(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
        "https://example.test/"+crateId,body.substring(0,5),null,null,null,body,
        tech.wenisch.contextcrate.storage.Hashing.sha256(body),"{}");
    value.assignCrate(crateId);return value;
  }
}
