package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.repository.*;

class CrateOverviewServiceTest {
  private final CrateRepository crates = mock(CrateRepository.class);
  private final CrateMemberRepository members = mock(CrateMemberRepository.class);
  private final SourceRepository sources = mock(SourceRepository.class);
  private final NormalizedDocumentRepository documents = mock(NormalizedDocumentRepository.class);
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final CrateAccessService access = mock(CrateAccessService.class);
  private final CrateOverviewService service =
      new CrateOverviewService(crates, members, sources, documents, chunks, access);

  private static CrateCount count(UUID crateId, long total) {
    return new CrateCount() {
      public UUID getCrateId() { return crateId; }

      public long getTotal() { return total; }
    };
  }

  @Test
  void countsAreMappedPerCrateAndCratesWithoutContentReportZero() {
    Crate populated = new Crate(UUID.randomUUID(), "Beta", "with content", UUID.randomUUID());
    Crate empty = new Crate(UUID.randomUUID(), "Alpha", null, UUID.randomUUID());
    UUID userId = UUID.randomUUID();
    when(access.memberships()).thenReturn(List.of(
        new CrateMember(populated.getId(), userId, CrateMember.Role.OWNER, userId),
        new CrateMember(empty.getId(), userId, CrateMember.Role.VIEWER, userId)));
    when(crates.findAllById(anyCollection())).thenReturn(List.of(populated, empty));
    when(documents.countCurrentByCrate(anyCollection())).thenReturn(List.of(count(populated.getId(), 7)));
    when(chunks.countByCrate(anyCollection())).thenReturn(List.of(count(populated.getId(), 21)));
    when(sources.countByCrate(anyCollection())).thenReturn(List.of(count(populated.getId(), 3)));
    when(members.countByCrate(anyCollection()))
        .thenReturn(List.of(count(populated.getId(), 2), count(empty.getId(), 1)));

    List<CrateOverviewService.CrateCard> cards = service.accessible();

    assertThat(cards).extracting(card -> card.crate().getName()).containsExactly("Alpha", "Beta");
    CrateOverviewService.CrateCard alpha = cards.getFirst(), beta = cards.get(1);
    assertThat(alpha.documents()).isZero();
    assertThat(alpha.sources()).isZero();
    assertThat(alpha.members()).isEqualTo(1);
    assertThat(alpha.role()).isEqualTo(CrateMember.Role.VIEWER);
    assertThat(beta.documents()).isEqualTo(7);
    assertThat(beta.chunks()).isEqualTo(21);
    assertThat(beta.sources()).isEqualTo(3);
    assertThat(beta.role()).isEqualTo(CrateMember.Role.OWNER);
  }

  @Test
  void administratorsSeeCratesTheyAreNotAMemberOfWithoutARole() {
    Crate foreign = new Crate(UUID.randomUUID(), "Foreign", null, UUID.randomUUID());
    when(access.memberships()).thenReturn(List.of());
    when(crates.findAll()).thenReturn(List.of(foreign));
    when(documents.countCurrentByCrate(anyCollection())).thenReturn(List.of());
    when(chunks.countByCrate(anyCollection())).thenReturn(List.of());
    when(sources.countByCrate(anyCollection())).thenReturn(List.of());
    when(members.countByCrate(anyCollection())).thenReturn(List.of());

    List<CrateOverviewService.CrateCard> cards = service.all();

    assertThat(cards).singleElement().satisfies(card -> {
      assertThat(card.role()).isNull();
      assertThat(card.documents()).isZero();
      assertThat(card.updatedOn()).isEqualTo(card.createdOn());
    });
  }

  @Test
  void recentCurrentDocumentsProduceAnActivitySparkline() {
    Crate crate = new Crate(UUID.randomUUID(), "Activity", null, UUID.randomUUID());
    UUID userId = UUID.randomUUID();
    NormalizedDocument document = new NormalizedDocument(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "https://example.test/document",
        "Document", "en", null, null, "content", "hash", "{}");
    document.assignCrate(crate.getId());
    when(access.memberships()).thenReturn(List.of(
        new CrateMember(crate.getId(), userId, CrateMember.Role.VIEWER, userId)));
    when(crates.findAllById(anyCollection())).thenReturn(List.of(crate));
    when(documents.countCurrentByCrate(anyCollection())).thenReturn(List.of());
    when(documents.findByCrateIdInAndCurrentVersionTrueAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
        anyCollection(), any(), any())).thenReturn(List.of(document));
    when(chunks.countByCrate(anyCollection())).thenReturn(List.of());
    when(sources.countByCrate(anyCollection())).thenReturn(List.of());
    when(members.countByCrate(anyCollection())).thenReturn(List.of());

    CrateOverviewService.IngestionSparkline activity = service.accessible().getFirst().ingestion();

    assertThat(activity.ingestedLastSevenDays()).isEqualTo(1);
    assertThat(activity.hasActivity()).isTrue();
    assertThat(activity.points()).contains(",").contains(" ");
  }

  @Test
  void noAccessibleCratesSkipsTheCountQueries() {
    when(access.memberships()).thenReturn(List.of());
    when(crates.findAllById(anyCollection())).thenReturn(List.of());

    assertThat(service.accessible()).isEmpty();
    verify(documents, never()).countCurrentByCrate(anyCollection());
  }
}
