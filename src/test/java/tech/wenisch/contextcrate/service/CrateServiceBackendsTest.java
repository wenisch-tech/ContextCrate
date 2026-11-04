package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.wenisch.contextcrate.backend.BackendChoice;
import tech.wenisch.contextcrate.backend.BackendChoice.SearchChoice;
import tech.wenisch.contextcrate.backend.BackendChoice.StorageChoice;
import tech.wenisch.contextcrate.backend.BackendValidationException;
import tech.wenisch.contextcrate.backend.ConnectionTest;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.index.RoutingSearchIndex;
import tech.wenisch.contextcrate.index.SearchBackendTester;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.storage.RoutingArtifactStore;
import tech.wenisch.contextcrate.storage.StorageConnectionTester;

class CrateServiceBackendsTest {
  CrateRepository crates = mock(CrateRepository.class);
  CrateMemberRepository members = mock(CrateMemberRepository.class);
  AppUserRepository users = mock(AppUserRepository.class);
  SystemSettingRepository settings = mock(SystemSettingRepository.class);
  CrateAccessService access = mock(CrateAccessService.class);
  CrateBackendSettingsRepository backends = mock(CrateBackendSettingsRepository.class);
  StorageConnectionTester storageTester = mock(StorageConnectionTester.class);
  SearchBackendTester searchTester = mock(SearchBackendTester.class);
  RoutingArtifactStore routingStore = mock(RoutingArtifactStore.class);
  RoutingSearchIndex routingIndex = mock(RoutingSearchIndex.class);
  SystemSetting policy = mock(SystemSetting.class);
  CrateService service;

  static final ConnectionTest OK = new ConnectionTest(true, List.of(new ConnectionTest.Step("a", "Step", ConnectionTest.Status.OK, null)));
  static final ConnectionTest FAILED = new ConnectionTest(false, List.of(new ConnectionTest.Step("a", "Bucket found", ConnectionTest.Status.FAILED, "The bucket 'x' does not exist.")));

  @BeforeEach
  void setUp() {
    var user = mock(AppUser.class);
    when(user.getId()).thenReturn(UUID.randomUUID());
    when(user.getEmail()).thenReturn("owner@example.test");
    when(user.getRole()).thenReturn("ADMIN");
    when(access.currentUser()).thenReturn(user);
    when(settings.findById(1)).thenReturn(Optional.of(policy));
    when(policy.getCrateCreationMode()).thenReturn(SystemSetting.CrateCreationMode.EVERYONE);
    when(crates.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    var properties = mock(ContextCrateProperties.class, RETURNS_DEEP_STUBS);
    service = new CrateService(crates, members, users, settings, mock(AuditLogRepository.class), access, mock(JdbcTemplate.class), properties, mock(SharedModelRepository.class));
    service.setBackends(backends, storageTester, searchTester, routingStore, routingIndex);
  }

  StorageChoice s3() { return new StorageChoice("S3", "https://s3.example.test", "eu-west-1", "my-bucket", "docs", "AKIA", "super-secret", true); }
  SearchChoice es() { return new SearchChoice("CUSTOM", "elasticsearch", "https://es.example.test:9200", "key", null, null, "idx", false); }

  @Test
  void aCrateWithoutChoiceUsesTheGlobalProvidersAndStoresNothing() {
    service.create("Plain", "d");
    service.create("Explicit global", "d", new BackendChoice(new StorageChoice("GLOBAL", null, null, null, null, null, null, null), new SearchChoice("GLOBAL", null, null, null, null, null, null, null)));
    verify(backends, never()).save(any());
    verifyNoInteractions(storageTester, searchTester);
  }

  @Test
  void dedicatedBackendsAreRefusedWhileThePolicyIsOff() {
    when(policy.isAllowCustomStorage()).thenReturn(false);
    when(policy.isAllowCustomSearchBackend()).thenReturn(false);
    assertThatThrownBy(() -> service.create("A", "d", new BackendChoice(s3(), null))).isInstanceOf(BackendValidationException.class)
        .satisfies(e -> assertThat(((BackendValidationException) e).field()).isEqualTo("storage")).hasMessageContaining("not enabled");
    assertThatThrownBy(() -> service.create("A", "d", new BackendChoice(null, es()))).isInstanceOf(BackendValidationException.class)
        .satisfies(e -> assertThat(((BackendValidationException) e).field()).isEqualTo("searchBackend"));
    assertThatThrownBy(() -> service.testStorage(s3())).isInstanceOf(BackendValidationException.class);
    verify(crates, never()).saveAndFlush(any());
    verifyNoInteractions(storageTester, searchTester);
  }

  @Test
  void aFailedConnectionTestStopsCreationAndReportsTheSteps() {
    when(policy.isAllowCustomStorage()).thenReturn(true);
    when(storageTester.test(any())).thenReturn(FAILED);
    assertThatThrownBy(() -> service.create("A", "d", new BackendChoice(s3(), null))).isInstanceOf(BackendValidationException.class)
        .hasMessageContaining("Bucket found").hasMessageContaining("does not exist")
        .satisfies(e -> assertThat(((BackendValidationException) e).test()).isSameAs(FAILED));
    verify(crates, never()).saveAndFlush(any());
    verify(backends, never()).save(any());
  }

  @Test
  void aWorkingDedicatedConfigurationIsStoredAndTheRoutingCachesAreReset() {
    when(policy.isAllowCustomStorage()).thenReturn(true);
    when(policy.isAllowCustomSearchBackend()).thenReturn(true);
    when(storageTester.test(any())).thenReturn(OK);
    when(searchTester.test(any())).thenReturn(OK);

    Crate crate = service.create("Dedicated", "d", new BackendChoice(s3(), es()));

    var saved = ArgumentCaptor.forClass(CrateBackendSettings.class);
    verify(backends).save(saved.capture());
    assertThat(saved.getValue().getCrateId()).isEqualTo(crate.getId());
    assertThat(saved.getValue().customStorage()).isTrue();
    assertThat(saved.getValue().s3Config().bucket()).isEqualTo("my-bucket");
    assertThat(saved.getValue().s3Config().secretKey()).isEqualTo("super-secret");
    assertThat(saved.getValue().customIndex()).isTrue();
    assertThat(saved.getValue().indexConfig().backend()).isEqualTo("elasticsearch");
    verify(routingStore).invalidate(crate.getId());
    verify(routingIndex).invalidate(crate.getId());
  }

  @Test
  void onlyThePartThatWasChosenIsStoredAndTested() {
    when(policy.isAllowCustomSearchBackend()).thenReturn(true);
    when(searchTester.test(any())).thenReturn(OK);
    service.create("Search only", "d", new BackendChoice(null, es()));
    var saved = ArgumentCaptor.forClass(CrateBackendSettings.class);
    verify(backends).save(saved.capture());
    assertThat(saved.getValue().customStorage()).isFalse();
    assertThat(saved.getValue().customIndex()).isTrue();
    verifyNoInteractions(storageTester);
  }

  @Test
  void choiceTextNeverPrintsSecrets() {
    assertThat(s3().toString()).doesNotContain("super-secret").doesNotContain("AKIA");
    assertThat(new SearchChoice("CUSTOM", "qdrant", "http://q", "api-secret", "u", "pw-secret", null, null).toString()).doesNotContain("api-secret").doesNotContain("pw-secret");
  }

  @Test
  void theChoiceIsNormalisedIntoConfigurations() {
    var config = new StorageChoice("s3", " https://s3.example.test ", " ", " b ", "", " ak ", "sk", null).toConfig();
    assertThat(config.endpoint()).isEqualTo("https://s3.example.test");
    assertThat(config.region()).isEqualTo("us-east-1");
    assertThat(config.bucket()).isEqualTo("b");
    assertThat(config.prefix()).isNull();
    assertThat(config.accessKey()).isEqualTo("ak");
    assertThat(config.pathStyle()).isTrue();
    var index = new SearchChoice("custom", " Elasticsearch ", " http://e ", "", " u ", "", "", true).toConfig();
    assertThat(index.backend()).isEqualTo("elasticsearch");
    assertThat(index.apiKey()).isNull();
    assertThat(index.username()).isEqualTo("u");
    assertThat(index.password()).isNull();
    assertThat(index.trustAllCertificates()).isTrue();
  }
}
