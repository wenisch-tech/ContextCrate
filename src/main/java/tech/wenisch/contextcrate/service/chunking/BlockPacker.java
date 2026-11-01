package tech.wenisch.contextcrate.service.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns structural blocks into chunks. A chunk never crosses a heading boundary, a code block is
 * never cut unless it alone exceeds the size, and small neighbouring blocks share a chunk so an
 * explanation and the snippet it introduces are retrieved together.
 */
public final class BlockPacker {
  private BlockPacker() {}

  public static List<ChunkDraft> pack(List<Block> blocks, ChunkOptions options) {
    List<ChunkDraft> result = new ArrayList<>();
    List<Block> buffer = new ArrayList<>();
    int length = 0;
    List<String> path = null;
    for (Block block : blocks) {
      if (block.text().isBlank()) continue;
      if (path != null && !path.equals(block.headingPath())) {
        flush(buffer, path, result);
        length = 0;
      }
      path = block.headingPath();
      String rendered = block.rendered();
      if (rendered.length() > options.size()) {
        flush(buffer, path, result);
        length = 0;
        split(block, options, result);
        continue;
      }
      int add = rendered.length() + (buffer.isEmpty() ? 0 : 2);
      if (!buffer.isEmpty() && length + add > options.size()) {
        flush(buffer, path, result);
        length = 0;
        add = rendered.length();
      }
      buffer.add(block);
      length += add;
    }
    if (path != null) flush(buffer, path, result);
    return result;
  }

  private static void split(Block block, ChunkOptions options, List<ChunkDraft> out) {
    String heading = heading(block.headingPath());
    if (block.kind() == Block.Kind.CODE) {
      // Leave room for nothing else: code chunks are stored raw, the fence is added on display.
      for (String part : TextSplitter.lines(block.text(), options.size()))
        out.add(new ChunkDraft(heading, part, ChunkDraft.CODE, block.language(), null, null, null));
    } else {
      for (String part : TextSplitter.prose(block.text(), options.size(), options.overlap()))
        out.add(ChunkDraft.text(heading, part));
    }
  }

  private static void flush(List<Block> buffer, List<String> path, List<ChunkDraft> out) {
    if (buffer.isEmpty()) return;
    String heading = heading(path);
    boolean onlyCode = buffer.stream().allMatch(b -> b.kind() == Block.Kind.CODE);
    if (onlyCode) {
      String language = buffer.get(0).language();
      StringBuilder text = new StringBuilder();
      for (Block block : buffer) text.append(text.isEmpty() ? "" : "\n\n").append(block.text());
      out.add(new ChunkDraft(heading, text.toString(), ChunkDraft.CODE, language, null, null, null));
    } else {
      StringBuilder text = new StringBuilder();
      for (Block block : buffer)
        text.append(text.isEmpty() ? "" : "\n\n").append(block.rendered());
      out.add(ChunkDraft.text(heading, text.toString()));
    }
    buffer.clear();
  }

  public static String heading(List<String> path) {
    if (path == null || path.isEmpty()) return null;
    String joined = String.join(" > ", path);
    return joined.length() > 1000 ? joined.substring(joined.length() - 1000) : joined;
  }

  /** The document body: headings and blocks in order, code keeping its fences. */
  public static String body(List<Block> blocks) {
    StringBuilder body = new StringBuilder();
    List<String> shown = List.of();
    for (Block block : blocks) {
      if (block.text().isBlank()) continue;
      if (!block.headingPath().equals(shown) && !block.headingPath().isEmpty()) {
        body.append(body.isEmpty() ? "" : "\n\n")
            .append(block.headingPath().get(block.headingPath().size() - 1));
        shown = block.headingPath();
        body.append('\n').append(block.rendered());
      } else {
        body.append(body.isEmpty() ? "" : "\n\n").append(block.rendered());
      }
    }
    return body.toString();
  }
}
