package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

class CrateLifecycleDeleteTest {
  CrateRepository crates = mock(CrateRepository.class);
  CrateAccessService access = mock(CrateAccessService.class);
  CratePurgeWorker worker = mock(CratePurgeWorker.class);
  PipelineWorkItemRepository work = mock(PipelineWorkItemRepository.class);
  CrateLifecycleService service;
  UUID id = UUID.randomUUID();
  Crate crate = new Crate(id, "Docs", null, UUID.randomUUID());

  @BeforeEach
  void setUp() {
    service = new CrateLifecycleService(crates, mock(SourceRepository.class), mock(IngestionJobRepository.class), mock(IngestionRunRepository.class), work, access,
        mock(AuditLogRepository.class), worker);
    when(crates.findById(id)).thenReturn(Optional.of(crate));
    var user = mock(AppUser.class);
    when(user.getEmail()).thenReturn("who@example.test");
    when(access.currentUser()).thenReturn(user);
  }

  @Test
  void anOwnerDeletesAfterTypingTheExactName() {
    service.delete(id, "Docs");
    assertThat(crate.getStatus()).isEqualTo(Crate.Status.PURGING);
    verify(work).deleteByCrateId(id);
    verify(worker).purge(id);
  }

  @Test
  void aWrongOrMissingNameChangesNothing() {
    assertThatThrownBy(() -> service.delete(id, "docs")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.delete(id, null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(crate.getStatus()).isEqualTo(Crate.Status.ACTIVE);
    verifyNoInteractions(worker);
  }

  @Test
  void aGlobalAdministratorNeedsNoElevation() {
    doThrow(new AccessDeniedException("no")).when(access).require(id, CrateMember.Role.OWNER);
    when(access.isAdmin()).thenReturn(true);
    service.delete(id, "Docs");
    verify(worker).purge(id);
  }

  @Test
  void everyoneElseIsRefused() {
    doThrow(new AccessDeniedException("no")).when(access).require(id, CrateMember.Role.OWNER);
    when(access.isAdmin()).thenReturn(false);
    assertThatThrownBy(() -> service.delete(id, "Docs")).isInstanceOf(AccessDeniedException.class);
    assertThat(crate.getStatus()).isEqualTo(Crate.Status.ACTIVE);
    verifyNoInteractions(worker);
  }

  @Test
  void aFailedDeletionCanBeStartedAgainButARunningOneCannot() {
    crate.deleting();
    crate.purgeFailed();
    service.delete(id, "Docs");
    assertThat(crate.getStatus()).isEqualTo(Crate.Status.PURGING);
    assertThatThrownBy(() -> service.delete(id, "Docs")).isInstanceOf(IllegalStateException.class);
  }
}
