package tech.wenisch.contextcrate.answer;

import java.util.List;
import java.util.function.Consumer;

public interface AnswerGenerationProvider {
  boolean available();
  String model();
  String complete(List<Message> messages) throws Exception;
  default String complete(List<Message> messages, double temperature) throws Exception { return complete(messages); }
  void stream(List<Message> messages, Consumer<String> delta) throws Exception;
  record Message(String role, String content) {}
}
