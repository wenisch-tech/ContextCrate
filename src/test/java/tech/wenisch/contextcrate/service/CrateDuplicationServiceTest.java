package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import tech.wenisch.contextcrate.repository.*;

class CrateDuplicationServiceTest {
  @Test
  void onlyGlobalAdministratorsMayDuplicate() {
    var access = mock(CrateAccessService.class);
    var crateService = mock(CrateService.class);
    when(access.isAdmin()).thenReturn(false);
    var service = new CrateDuplicationService(crateService, mock(CrateRepository.class), mock(SourceRepository.class), mock(IngestionJobRepository.class),
        mock(ExtractionRuleRepository.class), mock(AuditLogRepository.class), access, mock(JdbcTemplate.class));
    assertThatThrownBy(() -> service.duplicate(UUID.randomUUID(), "Copy", null, null)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(crateService);
  }
}
