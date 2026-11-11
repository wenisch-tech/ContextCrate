package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.RagSettingsRepository;

class RagSettingsFusionTest {
  private final UUID crateId = UUID.randomUUID();
  private final RagSettingsRepository repository = mock(RagSettingsRepository.class);
  private final RagSettings stored = new RagSettings(crateId, false, true, true, true, true, true, "revise-once", "hybrid", 8);

  private RagSettingsService service() {
    when(repository.findById(crateId)).thenReturn(Optional.of(stored));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    var properties = mock(ContextCrateProperties.class);
    var answering = mock(ContextCrateProperties.Answering.class);
    when(answering.sourceLimit()).thenReturn(20);
    when(properties.answering()).thenReturn(answering);
    return new RagSettingsService(repository, properties);
  }

  @Test
  void theVectorWeightDefaultsToHalfAndIsStoredWithinZeroAndOne() {
    assertThat(stored.getFusionVectorWeight()).isEqualTo(0.5);
    assertThat(service().updateFusion(crateId, 0.75).getFusionVectorWeight()).isEqualTo(0.75);
    assertThat(service().updateFusion(crateId, 0).getFusionVectorWeight()).isZero();
    assertThat(service().updateFusion(crateId, 1).getFusionVectorWeight()).isEqualTo(1.0);
  }

  @Test
  void weightsOutsideTheRangeAreRejected() {
    var service = service();
    assertThatThrownBy(() -> service.updateFusion(crateId, -0.1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateFusion(crateId, 1.01)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateFusion(crateId, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fusionIsAValidRetrievalModeEverywhere() {
    var result = service().update(crateId, false, true, true, true, true, true, "revise-once", "fusion", "standard", "fail-indexing", 8);
    assertThat(result.getRetrievalMode()).isEqualTo("fusion");
    assertThatThrownBy(() -> service().update(crateId, false, true, true, true, true, true, "revise-once", "rrf", "standard", "fail-indexing", 8))
        .hasMessageContaining("fusion");
    assertThat(new SearchIndex.SearchRequest(crateId, "q", 5, null, "chunk", "Fusion").mode()).isEqualTo("fusion");
    assertThatThrownBy(() -> new SearchIndex.SearchRequest(crateId, "q", 5, null, "chunk", "fusion").withVectorWeight(1.5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
