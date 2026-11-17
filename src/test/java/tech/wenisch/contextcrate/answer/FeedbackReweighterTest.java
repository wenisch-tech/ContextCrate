package tech.wenisch.contextcrate.answer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.*;

class FeedbackReweighterTest {
  private final UUID crate = UUID.randomUUID();
  private final AnswerFeedbackRepository feedback = mock(AnswerFeedbackRepository.class);
  private final AnswerFeedbackSourceRepository sources = mock(AnswerFeedbackSourceRepository.class);
  private final NormalizedDocumentRepository documents = mock(NormalizedDocumentRepository.class);
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final FeedbackReweighter reweighter = new FeedbackReweighter(feedback, sources, documents, provider);

  private SearchIndex.SearchHit hit(UUID doc, float score) {
    return new SearchIndex.SearchHit(UUID.randomUUID(), doc, UUID.randomUUID(), "chunk", "t", "u", 0, "s", score, null, null, null, "c", "c");
  }

  private RagSettings policy() { var p = new RagSettings(crate, false, true, true, true, true, true, "revise-once", "hybrid", 5); return p; }

  private NormalizedDocument document(UUID id, String identity) {
    var d = new NormalizedDocument(id, UUID.randomUUID(), UUID.randomUUID(), identity, "t", null, null, null, "b", "h", "{}");
    d.assignCrate(crate); d.version(UUID.randomUUID(), identity, 1);
    return d;
  }

  @Test void factorIsAverageOverNeutral() {
    assertEquals(1.0, FeedbackReweighter.factor(List.of(3)), 1e-9);
    assertEquals(5 / 3.0, FeedbackReweighter.factor(List.of(5, 5)), 1e-9);
    assertEquals(1 / 3.0, FeedbackReweighter.factor(List.of(1)), 1e-9);
    assertEquals(1.0, FeedbackReweighter.factor(List.of(1, 5)), 1e-9);
  }

  @Test void relevantFeedbackReordersAndIrrelevantIsIgnored() throws Exception {
    UUID good = UUID.randomUUID(), bad = UUID.randomUUID();
    when(documents.findByIdAndCrateId(good, crate)).thenReturn(Optional.of(document(good, "doc:good")));
    when(documents.findByIdAndCrateId(bad, crate)).thenReturn(Optional.of(document(bad, "doc:bad")));
    var fGood = new AnswerFeedback(UUID.randomUUID(), crate, AnswerFeedback.Channel.UI, "a", Instant.now().plusSeconds(60), "x", "how to A", "answer");
    fGood.rate(5, 5, null);
    var fBad = new AnswerFeedback(UUID.randomUUID(), crate, AnswerFeedback.Channel.UI, "a", Instant.now().plusSeconds(60), "y", "unrelated topic", "answer");
    fBad.rate(1, 1, null);
    when(feedback.findRatedByIdentities(eq(crate), anyCollection(), any())).thenReturn(List.of(fGood, fBad));
    when(sources.findByFeedbackIdIn(any())).thenReturn(List.of(
        new AnswerFeedbackSource(fGood.getId(), crate, 1, null, good, "doc:good", "t", "u"),
        new AnswerFeedbackSource(fBad.getId(), crate, 1, null, bad, "doc:bad", "t", "u")));
    when(provider.complete(anyList(), anyDouble())).thenAnswer(i -> {
      String prompt = ((List<AnswerGenerationProvider.Message>) i.getArgument(0)).get(1).content();
      return prompt.contains("how to A") ? "yes" : "no";
    });
    var results = new SearchIndex.SearchResults("q", "hybrid", List.of(hit(bad, 1.0f), hit(good, 0.8f)));
    var out = reweighter.apply(crate, "how do I do A", results, policy());
    assertEquals(good, out.hits().get(0).documentId());
    assertEquals(0.8f * 5 / 3f, out.hits().get(0).score(), 1e-4);
    assertEquals(1.0f, out.hits().get(1).score(), 1e-6, "feedback judged irrelevant must not change the score");
  }

  @Test void modelFailureKeepsOrder() throws Exception {
    UUID doc = UUID.randomUUID();
    when(documents.findByIdAndCrateId(doc, crate)).thenReturn(Optional.of(document(doc, "doc:1")));
    var f = new AnswerFeedback(UUID.randomUUID(), crate, AnswerFeedback.Channel.UI, "a", Instant.now().plusSeconds(60), "x", "q", "answer");
    f.rate(1, 1, null);
    when(feedback.findRatedByIdentities(eq(crate), anyCollection(), any())).thenReturn(List.of(f));
    when(sources.findByFeedbackIdIn(any())).thenReturn(List.of(new AnswerFeedbackSource(f.getId(), crate, 1, null, doc, "doc:1", "t", "u")));
    when(provider.complete(anyList(), anyDouble())).thenThrow(new RuntimeException("down"));
    var results = new SearchIndex.SearchResults("q", "hybrid", List.of(hit(doc, 0.7f)));
    assertEquals(0.7f, reweighter.apply(crate, "q", results, policy()).hits().get(0).score(), 1e-6);
  }
}
