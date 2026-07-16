package tech.wenisch.harvex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.config.HarvexProperties;
import tech.wenisch.harvex.crawl.HttpCrawler;
import tech.wenisch.harvex.domain.PipelineTypes.WorkStage;
import tech.wenisch.harvex.queue.*;

@Component
public class PipelineCoordinator {
  private final PipelineQueue queue;
  private final ObjectMapper mapper;
  private final HttpCrawler crawler;
  private final DocumentParser parser;
  private final DocumentIndexer indexer;
  private final HarvexProperties properties;
  private final MeterRegistry metrics;
  private final ThreadPoolExecutor executor;

  public PipelineCoordinator(
      PipelineQueue queue,
      ObjectMapper mapper,
      HttpCrawler crawler,
      DocumentParser parser,
      DocumentIndexer indexer,
      HarvexProperties properties,
      MeterRegistry metrics) {
    this.queue = queue;
    this.mapper = mapper;
    this.crawler = crawler;
    this.parser = parser;
    this.indexer = indexer;
    this.properties = properties;
    this.metrics = metrics;
    int concurrency = properties.worker().concurrency();
    var sequence = new java.util.concurrent.atomic.AtomicInteger();
    this.executor =
        new ThreadPoolExecutor(
            concurrency,
            concurrency,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(concurrency * 4),
            r -> {
              Thread t = new Thread(r, "harvex-worker-" + sequence.incrementAndGet());
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  @Scheduled(fixedDelayString = "${harvex.worker.poll-delay-ms:250}")
  public void poll() {
    for (WorkStage stage : WorkStage.values())
      if (supports(stage) && executor.getQueue().remainingCapacity() > 0)
        queue
            .claim(stage)
            .ifPresent(
                message -> {
                  try {
                    executor.execute(() -> process(message));
                  } catch (RejectedExecutionException e) {
                    queue.retry(message.id(), Duration.ZERO, "Local worker queue is full");
                  }
                });
  }

  private boolean supports(WorkStage stage) {
    String role = properties.role();
    return role.equals("all")
        || switch (stage) {
          case FETCH -> role.equals("crawler-http");
          case BROWSER_FETCH -> role.equals("crawler-browser");
          case PARSE, DISCOVERY -> role.equals("parser");
          case INDEX -> role.equals("indexer");
        };
  }

  private void process(PipelineMessage message) {
    long started = System.nanoTime();
    try {
      PipelinePayload payload = mapper.readValue(message.payload(), PipelinePayload.class);
      switch (message.stage()) {
        case FETCH -> crawler.fetch(payload, false);
        case BROWSER_FETCH -> crawler.fetch(payload, true);
        case PARSE -> parser.parse(payload);
        case INDEX -> indexer.index(payload);
        case DISCOVERY -> {}
      }
      queue.acknowledge(message.id());
      metrics.counter("harvex.pipeline.completed", "stage", message.stage().name()).increment();
    } catch (Exception e) {
      queue.retry(
          message.id(),
          Duration.ofSeconds(Math.min(300, 1L << Math.min(message.attempts(), 8))),
          safe(e));
      metrics.counter("harvex.pipeline.failed", "stage", message.stage().name()).increment();
    } finally {
      metrics
          .timer("harvex.pipeline.duration", "stage", message.stage().name())
          .record(Duration.ofNanos(System.nanoTime() - started));
    }
  }

  private static String safe(Exception e) {
    String message = e.getMessage();
    return (message == null ? e.getClass().getSimpleName() : message)
        .substring(
            0,
            Math.min(
                4000, message == null ? e.getClass().getSimpleName().length() : message.length()));
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
