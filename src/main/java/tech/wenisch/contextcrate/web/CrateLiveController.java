package tech.wenisch.contextcrate.web;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.CrateLiveViewService;

@RestController
@RequestMapping("/api/v1/crates/{crateId}/live")
public class CrateLiveController {
  private final CrateAccessService access;
  private final CrateLiveViewService live;
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
      Thread.ofPlatform().name("crate-live-", 0).daemon(true).factory());

  public CrateLiveController(CrateAccessService access, CrateLiveViewService live) {
    this.access = access; this.live = live;
  }

  @GetMapping("/snapshot")
  public CrateLiveSnapshot snapshot(@PathVariable UUID crateId,
      @RequestParam(required = false) UUID runId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    return live.snapshot(crateId, runId);
  }

  @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable UUID crateId,
      @RequestParam(required = false) UUID runId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    SseEmitter emitter = new SseEmitter(0L);
    AtomicLong lastVersion = new AtomicLong(Long.MIN_VALUE);
    var tasks = new CopyOnWriteArrayList<ScheduledFuture<?>>();
    Runnable cancel = () -> tasks.forEach(value -> value.cancel(false));
    emitter.onCompletion(cancel);
    emitter.onTimeout(cancel);
    emitter.onError(error -> cancel.run());
    try {
      CrateLiveSnapshot initial = live.snapshot(crateId, runId);
      lastVersion.set(initial.version());
      emitter.send(SseEmitter.event().name("snapshot").data(initial));
    } catch (Exception error) {
      emitter.completeWithError(error);
      return emitter;
    }
    tasks.add(scheduler.scheduleAtFixedRate(() -> {
      try {
        CrateLiveSnapshot snapshot = live.snapshot(crateId, runId);
        if (lastVersion.getAndSet(snapshot.version()) != snapshot.version())
          emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
      } catch (Exception error) {
        cancel.run(); emitter.completeWithError(error);
      }
    }, 2, 2, TimeUnit.SECONDS));
    tasks.add(scheduler.scheduleAtFixedRate(() -> {
      try {
        emitter.send(SseEmitter.event().comment("heartbeat"));
      } catch (Exception error) {
        cancel.run(); emitter.completeWithError(error);
      }
    }, 15, 15, TimeUnit.SECONDS));
    return emitter;
  }

  @PreDestroy
  void shutdown() { scheduler.shutdownNow(); }
}
