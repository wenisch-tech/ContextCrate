package tech.wenisch.contextcrate.web;

import java.util.concurrent.CompletableFuture;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.service.CrateAccessService;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crates/{crateId}/answers")
public class AnswerApiController {
  private final AnswerService answers;
  private final CrateAccessService access;
  public AnswerApiController(AnswerService answers, CrateAccessService access){this.answers=answers;this.access=access;}
  @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> answer(@PathVariable UUID crateId, @RequestBody AnswerService.Request request) throws Exception {
    access.require(crateId, CrateMember.Role.VIEWER);
    if(!answers.available(crateId))return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    var prepared=answers.prepare(request.withCrate(crateId));var emitter=new SseEmitter(180_000L);
    emitter.send(SseEmitter.event().name("sources").data(prepared.structuredSources()?prepared.sources():java.util.List.of()));
    CompletableFuture.runAsync(()->{try{if(prepared.strictGrounding()&&prepared.sources().isEmpty())send(emitter,"delta",java.util.Map.of("text","No answer was found in the knowledge base."));else { var result=answers.generate(prepared);if(result.verificationStatus()!=null)send(emitter,"verification",java.util.Map.of("status",result.verificationStatus()));send(emitter,"delta",java.util.Map.of("text",result.text())); }send(emitter,"complete",java.util.Map.of("sourcesAvailable",!prepared.sources().isEmpty()));emitter.complete();}catch(Exception e){send(emitter,"error",java.util.Map.of("error","Answer generation failed"));emitter.completeWithError(e);}});
    return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
  }
  private static void send(SseEmitter emitter,String name,Object data){try{emitter.send(SseEmitter.event().name(name).data(data));}catch(Exception ignored){}}
}
