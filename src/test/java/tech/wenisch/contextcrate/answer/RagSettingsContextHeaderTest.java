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

class RagSettingsContextHeaderTest {
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
  void defaultsToOffWithAThousandWords() {
    assertThat(stored.getContextHeaderVariant()).isEqualTo("off");
    assertThat(stored.getContextHeaderWords()).isEqualTo(1000);
  }

  @Test
  void storesAValidVariantCaseInsensitively() {
    var result = service().updateContextHeader(crateId, " Title-Summary ", 500);
    assertThat(result.getContextHeaderVariant()).isEqualTo("title-summary");
    assertThat(result.getContextHeaderWords()).isEqualTo(500);
  }

  @Test
  void rejectsUnknownVariantsAndOutOfRangeWords() {
    var service = service();
    assertThatThrownBy(() -> service.updateContextHeader(crateId, "everything", 1000)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateContextHeader(crateId, "title", 99)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateContextHeader(crateId, "title", 5001)).isInstanceOf(IllegalArgumentException.class);
  }
}
