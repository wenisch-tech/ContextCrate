package tech.wenisch.contextcrate.answer;

import java.util.List;
import java.util.function.Consumer;

public interface AnswerGenerationProvider {
  boolean available();
  String model();
  String complete(List<Message> messages) throws Exception;
  default String complete(List<Message> messages, double temperature) throws Exception { return complete(messages); }
  void stream(List<Message> messages, Consumer<String> delta) throws Exception;
  /**
   * One chat message. {@code images} are attached to the message as base64 {@code image_url} parts of an OpenAI-compatible
   * request; without images the content stays a plain string. Only the main answer request and its verification carry images.
   */
  record Message(String role, String content, List<Image> images) {
    public Message { images = images == null ? List.of() : List.copyOf(images); }
    public Message(String role, String content) { this(role, content, List.of()); }
    public Message withImages(List<Image> value) { return new Message(role, content, value); }
  }

  /** A picture to show the model: its MIME type (image/png, image/jpeg, image/gif or image/webp), the bytes, and the source it belongs to (0 = none). */
  record Image(String mime, byte[] data, int citation) {
    public Image(String mime, byte[] data) { this(mime, data, 0); }
    @Override public String toString() { return "Image[" + mime + ", " + (data == null ? 0 : data.length) + " bytes]"; }
  }
}
