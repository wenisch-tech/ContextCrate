package tech.wenisch.contextcrate.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.ProviderSettings;
import tech.wenisch.contextcrate.domain.SharedModel;
import tech.wenisch.contextcrate.repository.ProviderSettingsRepository;
import tech.wenisch.contextcrate.repository.SharedModelRepository;

class RuntimeProviderSharedModelTest {
  private static final String SECRET_URL = "https://internal.example/v1";
  private static final String SECRET_KEY = "sk-very-secret";
  private final UUID crateId = UUID.randomUUID();
  private final ProviderSettingsRepository repository = mock(ProviderSettingsRepository.class);
  private final SharedModelRepository shared = mock(SharedModelRepository.class);
  private final tech.wenisch.contextcrate.repository.SystemSettingRepository system = mock(tech.wenisch.contextcrate.repository.SystemSettingRepository.class);
  private final ProviderSettings settings = new ProviderSettings(crateId);
  private RuntimeProviderSettings providers;
  private SharedModel embedding, answer;

  @BeforeEach
  void setUp() {
    providers = new RuntimeProviderSettings(repository, mock(ContextCrateProperties.class, RETURNS_DEEP_STUBS), null, shared, system);
    when(repository.findById(crateId)).thenReturn(Optional.of(settings));
    embedding = new SharedModel(SharedModel.Kind.EMBEDDING);
    embedding.update("Company embeddings", SECRET_URL, "secret-model", SECRET_KEY, 768, 2000, null, true, false);
    answer = new SharedModel(SharedModel.Kind.ANSWER);
    answer.update("Company chat", SECRET_URL, "secret-chat", SECRET_KEY, null, null, 90, true, false);
    when(shared.findById(embedding.getId())).thenReturn(Optional.of(embedding));
    when(shared.findById(answer.getId())).thenReturn(Optional.of(answer));
    settings.selectSharedModels(embedding.getId(), null, answer.getId());
  }

  @Test
  void runtimeUsesTheSharedEndpointAndCredentials() {
    var e = providers.effectiveEmbedding(crateId);
    assertThat(e.provider()).isEqualTo("openai-compatible");
    assertThat(e.openaiBaseUrl()).isEqualTo(SECRET_URL);
    assertThat(e.openaiModel()).isEqualTo("secret-model");
    assertThat(e.openaiApiKey()).isEqualTo(SECRET_KEY);
    assertThat(e.openaiDimensions()).isEqualTo(768);
    var a = providers.effectiveAnswer(crateId);
    assertThat(a.baseUrl()).isEqualTo(SECRET_URL);
    assertThat(a.apiKey()).isEqualTo(SECRET_KEY);
    assertThat(a.timeoutSeconds()).isEqualTo(90);
  }

  @Test
  void displayViewsNeverCarryEndpointModelOrKey() {
    var e = providers.displayEmbedding(crateId);
    var a = providers.displayAnswer(crateId);
    assertThat(e.toString() + a)
        .doesNotContain(SECRET_URL, SECRET_KEY, "secret-model", "secret-chat");
    assertThat(e.openaiBaseUrl()).isNull();
    assertThat(a.baseUrl()).isNull();
  }

  @Test
  void clientsSeeTheSharedNameInsteadOfTheUpstreamModelId() {
    assertThat(providers.publicAnswerModel(crateId)).isEqualTo("Company chat");
  }

  @Test
  void aWithdrawnModelCannotBeSelectedAnew() {
    embedding.update("Company embeddings", SECRET_URL, "secret-model", null, 768, 2000, null, false, false);
    var other = new ProviderSettings(UUID.randomUUID());
    when(repository.findById(other.getCrateId())).thenReturn(Optional.of(other));
    var form = new RuntimeProviderSettings.ProviderForm(true, "openai-compatible", null, null, null, "cache", null,
        null, null, null, 768, 2000, false, false, "local", 30, null, null, null, "cache", null, null, null, null, 4000, 30,
        false, null, null, null, embedding.getId().toString(), null, null);

    assertThatThrownBy(() -> providers.update(other.getCrateId(), form))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not available");
  }

  @Test
  void aModelOfAnotherKindCannotBeSelected() {
    var form = new RuntimeProviderSettings.ProviderForm(true, "openai-compatible", null, null, null, "cache", null,
        null, null, null, 768, 2000, false, false, "local", 30, null, null, null, "cache", null, null, null, null, 4000, 30,
        false, null, null, null, null, null, embedding.getId().toString());

    assertThatThrownBy(() -> providers.update(crateId, form))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown shared model");
  }

  private RuntimeProviderSettings.ProviderForm customForm(String baseUrl, String localModel) {
    return new RuntimeProviderSettings.ProviderForm(true, "openai-compatible", localModel, null, null, "cache", null,
        baseUrl, "m", null, 768, 2000, false, false, "local", 30, null, null, null, "cache", null, null, null, null,
        4000, 30, false, null, null, null, null, null, null);
  }

  private void policy(boolean custom, boolean local) {
    var setting = mock(tech.wenisch.contextcrate.domain.SystemSetting.class);
    when(setting.isAllowCustomEndpoints()).thenReturn(custom);
    when(setting.isAllowLocalModels()).thenReturn(local);
    when(system.findById(1)).thenReturn(Optional.of(setting));
  }

  @Test
  void customEndpointsCanBeForbiddenButSharedModelsStillWork() {
    policy(false, true);
    settings.selectSharedModels(null, null, null);

    assertThatThrownBy(() -> providers.update(crateId, customForm("https://mine.example/v1", null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Custom endpoints are disabled");

    var shared = customForm(null, null);
    var selecting = new RuntimeProviderSettings.ProviderForm(true, "openai-compatible", null, null, null, "cache", null,
        null, null, null, 768, 2000, false, false, "local", 30, null, null, null, "cache", null, null, null, null,
        4000, 30, false, null, null, null, embedding.getId().toString(), null, null);
    assertThatCode(() -> providers.update(crateId, selecting)).doesNotThrowAnyException();
  }

  @Test
  void localModelSettingsCanBeFrozen() {
    policy(true, false);
    settings.selectSharedModels(null, null, null);

    assertThatThrownBy(() -> providers.update(crateId, customForm("x", "my/own-model")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("local models");
  }
}
