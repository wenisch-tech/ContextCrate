package tech.wenisch.harvex.web;

import java.util.concurrent.CompletableFuture;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.wenisch.harvex.answer.AnswerService;

@RestController
@RequestMapping("/api/v1/answers")
public class AnswerApiController {
  private final AnswerService answers;
  public AnswerApiController(AnswerService answers){this.answers=answers;}
  @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> answer(@RequestBody AnswerService.Request request) throws Exception {
    if(!answers.available())return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    var prepared=answers.prepare(request);var emitter=new SseEmitter(70_000L);
    emitter.send(SseEmitter.event().name("sources").data(prepared.structuredSources()?prepared.sources():java.util.List.of()));
    CompletableFuture.runAsync(()->{try{if(prepared.strictGrounding()&&prepared.sources().isEmpty())send(emitter,"delta",java.util.Map.of("text","No answer was found in the knowledge base."));else answers.stream(prepared,text->send(emitter,"delta",java.util.Map.of("text",text)));send(emitter,"complete",java.util.Map.of("sourcesAvailable",!prepared.sources().isEmpty()));emitter.complete();}catch(Exception e){send(emitter,"error",java.util.Map.of("error","Answer generation failed"));emitter.completeWithError(e);}});
    return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
  }
  private static void send(SseEmitter emitter,String name,Object data){try{emitter.send(SseEmitter.event().name(name).data(data));}catch(Exception ignored){}}
}
