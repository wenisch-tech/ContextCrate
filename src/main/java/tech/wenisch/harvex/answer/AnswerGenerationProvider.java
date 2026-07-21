package tech.wenisch.harvex.answer;

import java.util.List;
import java.util.function.Consumer;

public interface AnswerGenerationProvider {
  boolean available();
  String model();
  void stream(List<Message> messages, Consumer<String> delta) throws Exception;
  record Message(String role, String content) {}
}
