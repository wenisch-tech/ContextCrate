package tech.wenisch.contextcrate.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.PipelineTypes.*;
import tech.wenisch.contextcrate.domain.PipelineWorkItem;
import tech.wenisch.contextcrate.repository.PipelineWorkItemRepository;

@Component
@ConditionalOnProperty(name = "contextcrate.queue.backend", havingValue = "local", matchIfMissing = true)
public class LocalPipelineQueue implements PipelineQueue {
  private final PipelineWorkItemRepository repository;
  private final ContextCrateProperties properties;

  public LocalPipelineQueue(PipelineWorkItemRepository repository, ContextCrateProperties properties) {
    this.repository = repository;
    this.properties = properties;
  }

  @Override
  @Transactional
  public void publish(PipelineMessage message) {
    if (!repository.existsByStageAndIdempotencyKey(message.stage(), message.idempotencyKey()))
      {
        var item = new PipelineWorkItem(
              message.id(),
              message.stage(),
              message.payload(),
              message.correlationId(),
              message.idempotencyKey(),
              message.priority());
        item.assignCrate(message.crateId());
        repository.save(item);
      }
  }

  @Override
  @Transactional
  public Optional<PipelineMessage> claim(WorkStage stage) {
    var rows =
        repository.claimable(
            stage,
            List.of(WorkStatus.PENDING, WorkStatus.RETRY_WAITING),
            WorkStatus.PROCESSING,
            Instant.now(),
            PageRequest.of(0, 1));
    if (rows.isEmpty()) return Optional.empty();
    var row = rows.getFirst();
    row.claim(Instant.now().plusSeconds(properties.worker().leaseSeconds()));
    repository.save(row);
    return Optional.of(toMessage(row));
  }

  @Override
  @Transactional
  public void acknowledge(UUID id) {
    repository
        .findById(id)
        .ifPresent(
            w -> {
              w.complete();
              repository.save(w);
            });
  }

  @Override
  @Transactional
  public void retry(UUID id, Duration delay, String error) {
    repository
        .findById(id)
        .ifPresent(
            w -> {
              if (w.getAttempts() >= properties.worker().maxAttempts()) w.deadLetter(error);
              else w.retry(Instant.now().plus(delay), error);
              repository.save(w);
            });
  }

  @Override
  @Transactional
  public void fail(UUID id, String error) {
    repository
        .findById(id)
        .ifPresent(
            w -> {
              w.deadLetter(error);
              repository.save(w);
            });
  }

  @Override
  @Transactional
  public void requeue(UUID id) {
    repository
        .findById(id)
        .ifPresent(
            w -> {
              w.requeue();
              repository.save(w);
            });
  }

  @Override
  public long depth(WorkStage stage) {
    return repository.countByStageAndStatus(stage, WorkStatus.PENDING)
        + repository.countByStageAndStatus(stage, WorkStatus.RETRY_WAITING);
  }

  @Override
  public List<PipelineMessage> deadLetters() {
    return repository.findTop100ByStatusOrderByUpdatedAtDesc(WorkStatus.DEAD_LETTERED).stream()
        .map(LocalPipelineQueue::toMessage)
        .toList();
  }

  private static PipelineMessage toMessage(PipelineWorkItem w) {
    return new PipelineMessage(
        w.getId(),
        w.getCrateId(),
        w.getSchemaVersion(),
        w.getStage(),
        w.getPayload(),
        w.getCorrelationId(),
        w.getIdempotencyKey(),
        w.getPriority(),
        w.getAttempts(),
        w.getAvailableAt());
  }
}
