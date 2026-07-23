package tech.wenisch.contextcrate.queue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;

public interface PipelineQueue {
  void publish(PipelineMessage message);

  Optional<PipelineMessage> claim(WorkStage stage);

  void acknowledge(UUID id);

  void retry(UUID id, Duration delay, String error);

  void fail(UUID id, String error);

  void requeue(UUID id);

  long depth(WorkStage stage);

  List<PipelineMessage> deadLetters();
}
