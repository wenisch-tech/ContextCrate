package tech.wenisch.contextcrate.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStatus;
import tech.wenisch.contextcrate.domain.PipelineWorkItem;
import tech.wenisch.contextcrate.repository.PipelineWorkItemRepository;

@Component
@ConditionalOnProperty(name = "contextcrate.queue.backend", havingValue = "rabbitmq")
public class RabbitPipelineQueue implements PipelineQueue {
  private final RabbitTemplate rabbit;
  private final PipelineWorkItemRepository repository;
  private final ContextCrateProperties properties;

  public RabbitPipelineQueue(
      RabbitTemplate rabbit, PipelineWorkItemRepository repository, ContextCrateProperties properties) {
    this.rabbit = rabbit;
    this.repository = repository;
    this.properties = properties;
    rabbit.setMandatory(true);
  }

  @Override
  @Transactional
  public void publish(PipelineMessage m) {
    if (repository.existsByStageAndIdempotencyKey(m.stage(), m.idempotencyKey())) return;
    var item = new PipelineWorkItem(
        m.id(), m.stage(), m.payload(), m.correlationId(), m.idempotencyKey(), m.priority());
    item.assignCrate(m.crateId());
    repository.save(item);
    rabbit.convertAndSend(RabbitQueueConfiguration.EXCHANGE, key(m.stage()), m.id().toString());
  }

  @Override
  @Transactional
  public Optional<PipelineMessage> claim(WorkStage stage) {
    rabbit.receiveAndConvert("contextcrate." + key(stage), 1);
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
              if (w.getAttempts() >= properties.worker().maxAttempts()) {
                w.deadLetter(error);
                rabbit.convertAndSend(
                    RabbitQueueConfiguration.EXCHANGE,
                    key(w.getStage()) + ".dlq",
                    w.getId().toString());
              } else {
                w.retry(Instant.now().plus(delay), error);
                rabbit.convertAndSend(
                    RabbitQueueConfiguration.EXCHANGE, key(w.getStage()), w.getId().toString());
              }
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
              rabbit.convertAndSend(
                  RabbitQueueConfiguration.EXCHANGE,
                  key(w.getStage()) + ".dlq",
                  w.getId().toString());
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
              rabbit.convertAndSend(
                  RabbitQueueConfiguration.EXCHANGE, key(w.getStage()), w.getId().toString());
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
        .map(RabbitPipelineQueue::toMessage)
        .toList();
  }

  private static String key(WorkStage stage) {
    return stage.name().toLowerCase();
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
