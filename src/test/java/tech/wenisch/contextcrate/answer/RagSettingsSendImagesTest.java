package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.repository.RagSettingsRepository;

class RagSettingsSendImagesTest {
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
  void imagesAreSentToTheAnswerModelByDefaultAndCanBeSwitchedOff() {
    assertThat(stored.isSendImagesToModel()).isTrue();
    assertThat(service().updateSendImages(crateId, false).isSendImagesToModel()).isFalse();
    assertThat(service().updateSendImages(crateId, true).isSendImagesToModel()).isTrue();
  }
}
