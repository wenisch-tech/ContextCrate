package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStatus;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.CrateLiveViewService;

class CrateLiveControllerTest {
  private final CrateAccessService access = mock(CrateAccessService.class);
  private final CrateLiveViewService live = mock(CrateLiveViewService.class);
  private final CrateLiveController controller = new CrateLiveController(access, live);

  @AfterEach
  void shutdownScheduler() {
    controller.shutdown();
  }

  @Test
  void snapshotAndStreamUseTheSameViewerAuthorizedPayload() throws Exception {
    UUID crateId = UUID.randomUUID();
    CrateLiveSnapshot expected = snapshot(crateId);
    when(live.snapshot(crateId, null)).thenReturn(expected);

    assertThat(controller.snapshot(crateId, null)).isSameAs(expected);
    var emitter = controller.stream(crateId, null);
    emitter.complete();

    verify(access, times(2)).require(crateId, CrateMember.Role.VIEWER);
    verify(live, times(2)).snapshot(crateId, null);
    GetMapping mapping = CrateLiveController.class
        .getDeclaredMethod("stream", UUID.class, UUID.class).getAnnotation(GetMapping.class);
    assertThat(mapping.produces()).containsExactly(MediaType.TEXT_EVENT_STREAM_VALUE);
  }

  private static CrateLiveSnapshot snapshot(UUID crateId) {
    Map<WorkStatus, Long> statuses = Map.of(WorkStatus.PENDING, 0L);
    return new CrateLiveSnapshot(crateId, Instant.now(), 1L,
        new CrateLiveSnapshot.Metrics(0, 0, 0, 0, 0, 0, 0), null,
        Map.of(WorkStage.WEB_FETCH, statuses), List.of(), null);
  }
}
