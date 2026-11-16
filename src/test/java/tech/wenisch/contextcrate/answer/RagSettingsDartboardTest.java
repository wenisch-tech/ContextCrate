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
import tech.wenisch.contextcrate.repository.RagSettingsRepository;

class RagSettingsDartboardTest {
  private final UUID crateId = UUID.randomUUID();
  private final RagSettingsRepository repository = mock(RagSettingsRepository.class);
  private final RagSettings stored = new RagSettings(crateId, false, true, true, true, true, true, "revise-once", "hybrid", 8);

  private RagSettingsService service() {
    when(repository.findById(crateId)).thenReturn(Optional.of(stored));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    var properties = mock(ContextCrateProperties.class);
    when(properties.answering()).thenReturn(mock(ContextCrateProperties.Answering.class));
    return new RagSettingsService(repository, properties);
  }

  @Test
  void defaultsAreOffThreeTimesOneOneAndPointOne() {
    assertThat(stored.isDartboardEnabled()).isFalse();
    assertThat(stored.getDartboardOversampling()).isEqualTo(3);
    assertThat(stored.getDartboardRelevanceWeight()).isEqualTo(1.0);
    assertThat(stored.getDartboardDiversityWeight()).isEqualTo(1.0);
    assertThat(stored.getDartboardSigma()).isEqualTo(0.1);
  }

  @Test
  void storesValidValues() {
    var result = service().updateDartboard(crateId, true, 5, 2.0, 0.5, 0.25);
    assertThat(result.isDartboardEnabled()).isTrue();
    assertThat(result.getDartboardOversampling()).isEqualTo(5);
    assertThat(result.getDartboardRelevanceWeight()).isEqualTo(2.0);
    assertThat(result.getDartboardDiversityWeight()).isEqualTo(0.5);
    assertThat(result.getDartboardSigma()).isEqualTo(0.25);
    assertThat(service().updateDartboard(crateId, true, 3, 1, 0, 0.1).getDartboardDiversityWeight()).isZero();
  }

  @Test
  void rejectsValuesOutsideTheRanges() {
    var service = service();
    assertThatThrownBy(() -> service.updateDartboard(crateId, true, 1, 1, 1, 0.1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateDartboard(crateId, true, 11, 1, 1, 0.1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateDartboard(crateId, true, 3, -1, 1, 0.1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateDartboard(crateId, true, 3, 1, 11, 0.1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateDartboard(crateId, true, 3, 0, 0, 0.1)).hasMessageContaining("both");
    assertThatThrownBy(() -> service.updateDartboard(crateId, true, 3, 1, 1, 0.001)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateDartboard(crateId, true, 3, 1, 1, 1.5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateDartboard(crateId, true, 3, 1, 1, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateDartboard(crateId, true, 3, Double.NaN, 1, 0.1)).isInstanceOf(IllegalArgumentException.class);
  }
}
