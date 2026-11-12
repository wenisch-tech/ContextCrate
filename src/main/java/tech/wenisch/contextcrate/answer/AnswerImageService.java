package tech.wenisch.contextcrate.answer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.DocumentImage;
import tech.wenisch.contextcrate.metrics.CrateMetrics;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;
import tech.wenisch.contextcrate.repository.DocumentImageRepository;
import tech.wenisch.contextcrate.service.ImageBytes;

/**
 * Finds the pictures behind the retrieved sources, so they can be shown to the answer model. A source is an image
 * source when its chunk carries a media address; the picture is the indexed image of the same document with that address.
 * Anything that cannot be read is skipped: a missing picture must never stop an answer.
 */
@Service
public class AnswerImageService {
  private static final Logger log = LoggerFactory.getLogger(AnswerImageService.class);
  /** At most this many pictures, and this many bytes in total, go into one answer request. */
  public static final int MAX_IMAGES = 4;
  public static final long MAX_TOTAL_BYTES = 12L * 1024 * 1024;

  private final DocumentChunkRepository chunks;
  private final DocumentImageRepository images;
  private final ImageBytes bytes;
  private final RagSettingsService rag;
  private CrateMetrics metrics = CrateMetrics.noop();

  public AnswerImageService(DocumentChunkRepository chunks, DocumentImageRepository images, ImageBytes bytes, RagSettingsService rag) {
    this.chunks = chunks; this.images = images; this.bytes = bytes; this.rag = rag;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setMetrics(CrateMetrics metrics) { this.metrics = metrics; }

  /** The pictures of the prepared answer's sources, in source order, each labeled with its citation number. Never throws. */
  public List<AnswerGenerationProvider.Image> imagesFor(AnswerService.Prepared prepared) {
    UUID crateId = prepared.crateId();
    List<AnswerGenerationProvider.Image> found = new ArrayList<>();
    long total = 0;
    int skipped = 0;
    try {
      int maxBytes = rag.current(crateId).getImageMaxBytes();
      for (AnswerService.Source source : prepared.sources()) {
        if (found.size() >= MAX_IMAGES) break;
        String media = chunks.findByIdAndCrateId(source.chunkId(), crateId).map(DocumentChunk::getMediaUrl).orElse(null);
        if (media == null || media.isBlank()) continue;
        DocumentImage image = images.findByDocumentIdAndCrateIdOrderByCreatedAt(source.documentId(), crateId).stream()
            .filter(i -> i.getStatus() == DocumentImage.Status.DESCRIBED && (media.equals(i.getViewUrl()) || media.equals(i.getUrl())))
            .findFirst().orElse(null);
        if (image == null) continue;
        try {
          byte[] data = bytes.load(image, maxBytes);
          String mime = ImageBytes.sniff(data);
          if (mime == null || total + data.length > MAX_TOTAL_BYTES) { skipped++; continue; }
          total += data.length;
          found.add(new AnswerGenerationProvider.Image(mime, data, source.citation()));
        } catch (Exception e) {
          skipped++;
          log.warn("An image could not be attached to the answer. crateId={}, imageId={}: {}", crateId, image.getId(), e.toString());
        }
      }
    } catch (RuntimeException e) {
      log.warn("Looking up images for the answer failed. crateId={}: {}", crateId, e.toString());
    }
    if (skipped > 0) metrics.count("answer.images", crateId, "outcome", "skipped");
    return found;
  }
}
