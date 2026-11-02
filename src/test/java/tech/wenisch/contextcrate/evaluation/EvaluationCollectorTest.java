package tech.wenisch.contextcrate.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.domain.EvaluationCase;
import tech.wenisch.contextcrate.domain.EvaluationSettings;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;
import tech.wenisch.contextcrate.queue.PipelineMessage;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.EvaluationCaseRepository;

class EvaluationCollectorTest {
  private final UUID crateId = UUID.randomUUID();
  private final EvaluationSettingsService settings = mock(EvaluationSettingsService.class);
  private final EvaluationCaseRepository cases = mock(EvaluationCaseRepository.class);
  private final PipelineQueue queue = mock(PipelineQueue.class);
  private final EvaluationSettings config = new EvaluationSettings(crateId);
  private EvaluationCollector collector;

  private EvaluationCollector collector(ThreadPoolExecutor executor) {
    when(settings.current(crateId)).thenReturn(config);
    when(settings.judgeAvailable(crateId)).thenReturn(true);
    collector = new EvaluationCollector(settings, cases, queue, new ObjectMapper(), executor);
    return collector;
  }

  private ThreadPoolExecutor direct() {
    // Runs the hand-over job on the calling thread so tests can assert synchronously.
    return new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10)) {
      @Override public void execute(Runnable command) { command.run(); }
    };
  }

  @AfterEach
  void close() { if (collector != null) collector.close(); }

  private AnswerService.Prepared prepared() {
    var source = new AnswerService.Source(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Title", "https://example.test/a", 0, "snippet", 1f, "chunk text");
    return new AnswerService.Prepared(crateId, "private question", List.of(), "hybrid", List.of(source), "alice", false, true, true, false, "revise-once");
  }

  @Test
  void disabledEvaluationStoresAndQueuesNothing() {
    config.update(false, 1.0, 5, 20, true, true, true, true, false, 30);

    collector(direct()).offer(prepared(), "an answer");

    verifyNoInteractions(cases, queue);
  }

  @Test
  void anEnabledCrateStoresTheSnapshotAndQueuesOnlyTheCaseId() throws Exception {
    config.update(true, 1.0, 5, 20, true, true, true, true, false, 30);

    collector(direct()).offer(prepared(), "an answer");

    var saved = ArgumentCaptor.forClass(EvaluationCase.class);
    verify(cases).save(saved.capture());
    assertThat(saved.getValue().getQuestion()).isEqualTo("private question");
    assertThat(saved.getValue().getAnswer()).isEqualTo("an answer");
    assertThat(saved.getValue().getSnapshotJson()).contains("chunk text");
    var message = ArgumentCaptor.forClass(PipelineMessage.class);
    verify(queue).publish(message.capture());
    assertThat(message.getValue().stage()).isEqualTo(WorkStage.EVALUATE);
    assertThat(message.getValue().crateId()).isEqualTo(crateId);
    // The queue payload (kept in the pipeline table) contains ids only, no user or document text.
    assertThat(message.getValue().payload()).contains(saved.getValue().getId().toString())
        .doesNotContain("private question").doesNotContain("an answer").doesNotContain("chunk text");
  }

  @Test
  void aCrateWithoutAJudgeIsNotQueued() {
    config.update(true, 1.0, 5, 20, true, true, true, true, false, 30);
    var collector = collector(direct());
    when(settings.judgeAvailable(crateId)).thenReturn(false);

    collector.offer(prepared(), "an answer");

    verifyNoInteractions(cases, queue);
  }

  @Test
  void answersGeneratedWhileSuppressedAreNotEvaluated() throws Exception {
    config.update(true, 1.0, 5, 20, true, true, true, true, false, 30);
    var collector = collector(direct());

    try (var ignored = EvaluationCollector.suppress()) { collector.offer(prepared(), "an answer"); }

    verifyNoInteractions(cases, queue);
    collector.offer(prepared(), "an answer"); // suppression ends with the scope
    verify(queue).publish(any());
  }

  @Test
  void aBlankAnswerIsIgnored() {
    config.update(true, 1.0, 5, 20, true, true, true, true, false, 30);
    collector(direct()).offer(prepared(), "  ");
    verifyNoInteractions(cases, queue);
  }

  @Test
  void samplingSkipsTheShareOfAnswersThatIsNotSelected() {
    config.update(true, 0.01, 5, 20, true, true, true, true, false, 30);
    var collector = collector(direct());
    for (int i = 0; i < 200; i++) collector.offer(prepared(), "an answer");
    // With a 1% rate, 200 answers almost never produce more than a handful of cases.
    verify(cases, atMost(20)).save(any());
  }

  @Test
  void aBlockedOrFullHandOverQueueNeverDelaysOrBreaksTheCaller() throws Exception {
    config.update(true, 1.0, 5, 20, true, true, true, true, false, 30);
    CountDownLatch release = new CountDownLatch(1);
    when(cases.save(any())).thenAnswer(i -> { release.await(10, TimeUnit.SECONDS); return i.getArgument(0); });
    var executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    var collector = collector(executor);

    long started = System.nanoTime();
    for (int i = 0; i < 50; i++) collector.offer(prepared(), "an answer"); // one running, one waiting, the rest rejected
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(millis).isLessThan(1_000); // the slow store happens off the calling thread
    release.countDown();
  }
}
