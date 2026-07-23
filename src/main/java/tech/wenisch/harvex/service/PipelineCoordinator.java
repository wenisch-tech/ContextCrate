package tech.wenisch.harvex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  private final ExtractionService extractor;
  private final DocumentIndexer indexer;
  private final HarvexProperties properties;
  private final MeterRegistry metrics;
  private final ThreadPoolExecutor executor;
  private static final Logger log = LoggerFactory.getLogger(PipelineCoordinator.class);

  public PipelineCoordinator(
      PipelineQueue queue,
      ObjectMapper mapper,
      HttpCrawler crawler,
      DocumentParser parser,
      ExtractionService extractor,
      DocumentIndexer indexer,
      HarvexProperties properties,
      MeterRegistry metrics) {
    this.queue = queue;
    this.mapper = mapper;
    this.crawler = crawler;
    this.parser = parser;
    this.extractor = extractor;
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
    return role.equals("all") || role.equals("control-plane")
        || switch (stage) {
          case FETCH -> role.equals("crawler-http");
          case BROWSER_FETCH -> role.equals("crawler-browser");
          case PARSE, DISCOVERY -> role.equals("parser");
          case EXTRACT -> role.equals("extractor");
          case INDEX -> role.equals("indexer");
        };
  }

   private void process(PipelineMessage message) {
     long started = System.nanoTime();
     try {
       PipelinePayload payload = mapper.readValue(message.payload(), PipelinePayload.class);
       log.info("Processing {} job (attempt {}): run={}, entity={}",
           message.stage(), message.attempts() + 1, payload.runId(), payload.entityId());

       switch (message.stage()) {
         case FETCH -> crawler.fetch(payload, false);
         case BROWSER_FETCH -> crawler.fetch(payload, true);
         case PARSE -> parser.parse(payload);
         case EXTRACT -> extractor.extract(payload);
         case INDEX -> indexer.index(payload);
         case DISCOVERY -> {}
       }

       queue.acknowledge(message.id());
       metrics.counter("harvex.pipeline.completed", "stage", message.stage().name()).increment();
       log.info("Completed {} job: run={}, entity={}, time={}ms",
           message.stage(), payload.runId(), payload.entityId(),
           Duration.ofNanos(System.nanoTime() - started).toMillis());
     } catch (Exception e) {
       queue.retry(
           message.id(),
           Duration.ofSeconds(Math.min(300, 1L << Math.min(message.attempts(), 8))),
           safe(e));
       metrics.counter("harvex.pipeline.failed", "stage", message.stage().name()).increment();
       log.error("Failed {} job: run={}, entity={}, error={}",
           message.stage(), message.payload(), message.id(), safe(e));
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
