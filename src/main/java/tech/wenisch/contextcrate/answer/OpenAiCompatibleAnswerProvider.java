package tech.wenisch.contextcrate.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;

@Component
public class OpenAiCompatibleAnswerProvider implements AnswerGenerationProvider {
  private final RuntimeProviderSettings settings;
  private final ObjectMapper mapper;
  private final HttpClient client;

  public OpenAiCompatibleAnswerProvider(RuntimeProviderSettings settings, ObjectMapper mapper) {
    this.settings = settings; this.mapper = mapper;
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();
  }
  @Override public boolean available() {
    var c=settings.effectiveAnswer(); return c.enabled() && c.baseUrl()!=null&&!c.baseUrl().isBlank()&&c.model()!=null&&!c.model().isBlank();
  }
  @Override public String model() { return settings.effectiveAnswer().model(); }
  @Override public String complete(List<Message> messages) throws Exception {
    return complete(messages, settings.effectiveAnswer().temperature());
  }
  @Override public String complete(List<Message> messages,double temperature) throws Exception {
    if (!available()) throw new IllegalStateException("Answer generation is not configured");
    var c=settings.effectiveAnswer();
    var response=client.send(request(c).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
        Map.of("model",c.model(),"stream",false,"temperature",temperature,"max_tokens",c.maxOutputTokens(),"messages",messages)))).build(),HttpResponse.BodyHandlers.ofString());
    if(response.statusCode()/100!=2)throw new IllegalStateException("Answer provider returned HTTP "+response.statusCode());
    var content=mapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
    if(content.isMissingNode()||content.isNull())throw new IllegalStateException("Answer provider returned no completion content");
    return content.asText();
  }
  @Override public void stream(List<Message> messages, Consumer<String> delta) throws Exception {
    if (!available()) throw new IllegalStateException("Answer generation is not configured");
    var c=settings.effectiveAnswer();
    var request=request(c);
    var body=Map.of("model",c.model(),"stream",true,"temperature",c.temperature(),"max_tokens",c.maxOutputTokens(),"messages",messages);
    var response=client.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofLines());
    if(response.statusCode()/100!=2)throw new IllegalStateException("Answer provider returned HTTP "+response.statusCode());
    try(var lines=response.body()) { lines.filter(line->line.startsWith("data:")).map(line->line.substring(5).trim()).takeWhile(data->!data.equals("[DONE]")).forEach(data->{try{var text=mapper.readTree(data).path("choices").path(0).path("delta").path("content");if(!text.isMissingNode()&&!text.isNull())delta.accept(text.asText());}catch(Exception e){throw new IllegalStateException("Invalid streaming response from answer provider",e);}}); }
  }
  private static HttpRequest.Builder request(RuntimeProviderSettings.Answering c) {
    var request=HttpRequest.newBuilder(URI.create(c.baseUrl().replaceAll("/+$","")+"/chat/completions"))
        .timeout(Duration.ofSeconds(c.timeoutSeconds())).header("Content-Type","application/json");
    if(c.apiKey()!=null&&!c.apiKey().isBlank())request.header("Authorization","Bearer "+c.apiKey());
    if(c.headers()!=null)for(String h:c.headers().split("\\n")){int colon=h.indexOf(':');if(colon>0)request.header(h.substring(0,colon).trim(),h.substring(colon+1).trim());}
    return request;
  }
}
