package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.domain.PipelineTypes.ExtractionType;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.*;

@ExtendWith(MockitoExtension.class)
class ExtractionServiceTest {
  @Mock NormalizedDocumentRepository documents;
  @Mock DocumentChunkRepository chunks;
  @Mock ExtractionRuleRepository rules;
  @Mock ExtractionResultRepository results;
  @Mock PipelineQueue queue;

  @Test
  void extractsValidatedIpAddressesFromChunks() {
    var service =
        new ExtractionService(
            documents,
            chunks,
            rules,
            results,
            queue,
            List.of(new IpAddressExtractionStrategy(), new RegexExtractionStrategy()));
    UUID runId = UUID.randomUUID();
    UUID crateId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID chunkId = UUID.randomUUID();
    var document =
        new NormalizedDocument(
            documentId,
            runId,
            UUID.randomUUID(),
            "https://example.test/",
            "Example",
            "en",
            null,
            null,
            "body",
            "hash",
            "{}");
    document.assignCrate(crateId);
    var chunk =
        new DocumentChunk(
            chunkId,
            documentId,
            0,
            null,
            "Primary 192.168.1.10, invalid 999.999.999.999, IPv6 2001:db8::1.",
            "hash");
    chunk.assignCrate(crateId);
    var rule =
        new ExtractionRule(UUID.randomUUID(), "IP addresses", ExtractionType.IP_ADDRESS, null, true);
    rule.assignCrate(crateId);

    when(documents.findById(documentId)).thenReturn(Optional.of(document));
    when(rules.findByCrateIdAndEnabledTrueOrderByNameAsc(crateId)).thenReturn(List.of(rule));
    when(chunks.findByDocumentIdOrderByOrdinal(documentId)).thenReturn(List.of(chunk));
    when(results.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.extract(new PipelinePayload(runId, documentId));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<ExtractionResult>> saved = ArgumentCaptor.forClass(Iterable.class);
    verify(results).deleteByDocumentId(documentId);
    verify(results).saveAll(saved.capture());
    List<ExtractionResult> values = new ArrayList<>();
    saved.getValue().forEach(values::add);
    assertThat(values)
        .extracting(ExtractionResult::getMatchedValue)
        .containsExactly("192.168.1.10", "2001:db8::1");
    assertThat(values).allSatisfy(result -> assertThat(result.getContextBefore() + result.getContextAfter()).isNotBlank());
  }
}
