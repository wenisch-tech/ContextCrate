package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.DocumentImage;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;
import tech.wenisch.contextcrate.repository.DocumentImageRepository;
import tech.wenisch.contextcrate.service.ImageBytes;
import tech.wenisch.contextcrate.storage.Hashing;

class AnswerImageServiceTest {
  private final UUID crateId = UUID.randomUUID();
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final DocumentImageRepository images = mock(DocumentImageRepository.class);
  private final ImageBytes bytes = mock(ImageBytes.class);
  private final RagSettingsService rag = mock(RagSettingsService.class);
  private final AnswerImageService service = new AnswerImageService(chunks, images, bytes, rag);
  private final List<AnswerService.Source> sources = new ArrayList<>();
  private final byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

  AnswerImageServiceTest() {
    when(rag.current(crateId)).thenReturn(new RagSettings(crateId, false, true, true, true, true, true, "revise-once", "hybrid", 8));
  }

  /** A retrieved source; when {@code media} is given its chunk is an image chunk and an image row with that address exists. */
  private UUID source(int citation, String media, DocumentImage.Status status) throws Exception {
    UUID chunkId = UUID.randomUUID(), documentId = UUID.randomUUID();
    sources.add(new AnswerService.Source(citation, chunkId, documentId, UUID.randomUUID(), "T", "https://example.test/" + citation, 0, "s", 1f, "content"));
    var chunk = new DocumentChunk(chunkId, documentId, 0, "H", "text", Hashing.sha256("text" + citation));
    if (media != null) chunk.media(media);
    when(chunks.findByIdAndCrateId(chunkId, crateId)).thenReturn(Optional.of(chunk));
    if (media != null) {
      var image = new DocumentImage(crateId, documentId, UUID.randomUUID(), media, media, "alt", null, null, null);
      if (status == DocumentImage.Status.DESCRIBED) image.described("a diagram", "vision");
      when(images.findByDocumentIdAndCrateIdOrderByCreatedAt(documentId, crateId)).thenReturn(List.of(image));
      when(bytes.load(image, 5_242_880)).thenReturn(png);
    }
    return chunkId;
  }

  private AnswerService.Prepared prepared() {
    return new AnswerService.Prepared(crateId, "q", List.of(), "hybrid", List.copyOf(sources), "alice", false, true, true, false, "revise-once");
  }

  @Test
  void onlyImageSourcesGetTheirPictureAndKeepTheirCitation() throws Exception {
    source(1, null, null);
    source(2, "https://example.test/diagram.png", DocumentImage.Status.DESCRIBED);

    var found = service.imagesFor(prepared());

    assertThat(found).hasSize(1);
    assertThat(found.getFirst().mime()).isEqualTo("image/png");
    assertThat(found.getFirst().citation()).isEqualTo(2);
    assertThat(found.getFirst().data()).isEqualTo(png);
  }

  @Test
  void imagesThatWereNeverDescribedOrCannotBeReadAreSkippedWithoutFailing() throws Exception {
    source(1, "https://example.test/skipped.png", DocumentImage.Status.SKIPPED);
    source(2, "https://example.test/broken.png", DocumentImage.Status.DESCRIBED);
    source(3, "https://example.test/also-broken.png", DocumentImage.Status.DESCRIBED);
    doThrow(new IllegalStateException("HTTP 404")).when(bytes).load(any(), anyInt());

    assertThat(service.imagesFor(prepared())).isEmpty();
  }

  @Test
  void aFileThatIsNotAPngJpegGifOrWebpIsNotSent() throws Exception {
    source(1, "https://example.test/x.svg", DocumentImage.Status.DESCRIBED);
    when(bytes.load(any(), anyInt())).thenReturn("<svg/>".getBytes());

    assertThat(service.imagesFor(prepared())).isEmpty();
  }

  @Test
  void atMostFourImagesAreSentInSourceOrder() throws Exception {
    for (int i = 1; i <= 6; i++) source(i, "https://example.test/" + i + ".png", DocumentImage.Status.DESCRIBED);

    var found = service.imagesFor(prepared());

    assertThat(found).hasSize(AnswerImageService.MAX_IMAGES);
    assertThat(found).extracting(AnswerGenerationProvider.Image::citation).containsExactly(1, 2, 3, 4);
  }

  @Test
  void theTotalSizeIsLimitedToo() throws Exception {
    source(1, "https://example.test/a.png", DocumentImage.Status.DESCRIBED);
    source(2, "https://example.test/b.png", DocumentImage.Status.DESCRIBED);
    byte[] big = new byte[(int) (AnswerImageService.MAX_TOTAL_BYTES / 2) + 1];
    big[0] = (byte) 0x89; big[1] = 'P';
    when(bytes.load(any(), anyInt())).thenReturn(big);

    assertThat(service.imagesFor(prepared())).hasSize(1);
  }

  @Test
  void aLookupFailureNeverStopsTheAnswer() {
    when(rag.current(crateId)).thenThrow(new IllegalStateException("database down"));

    assertThat(service.imagesFor(prepared())).isEmpty();
  }
}
