package tech.wenisch.contextcrate.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.backend.ConnectionTest;
import tech.wenisch.contextcrate.domain.CrateBackendSettings;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.index.SearchBackendTester;
import tech.wenisch.contextcrate.repository.CrateBackendSettingsRepository;
import tech.wenisch.contextcrate.storage.StorageConnectionTester;

/** Read access to a crate's storage and search backend, without secrets, and a way to re-test them. */
@Service
public class CrateBackendService {
  private final CrateBackendSettingsRepository settings;
  private final CrateAccessService access;
  private final StorageConnectionTester storageTester;
  private final SearchBackendTester searchTester;

  public CrateBackendService(CrateBackendSettingsRepository settings, CrateAccessService access, StorageConnectionTester storageTester, SearchBackendTester searchTester) {
    this.settings = settings; this.access = access; this.storageTester = storageTester; this.searchTester = searchTester;
  }

  /** The configuration of the crate; secrets are reported only as "configured". */
  public Map<String, Object> view(UUID crateId) {
    access.require(crateId, CrateMember.Role.OWNER);
    var row = settings.findById(crateId);
    Map<String, Object> storage = new LinkedHashMap<>();
    Map<String, Object> search = new LinkedHashMap<>();
    if (row.isPresent() && row.get().customStorage()) {
      var c = row.get().s3Config();
      storage.put("mode", "S3");
      storage.put("endpoint", c.endpoint());
      storage.put("region", c.region());
      storage.put("bucket", c.bucket());
      storage.put("prefix", c.prefix());
      storage.put("accessKey", c.accessKey());
      storage.put("pathStyle", c.pathStyle());
      storage.put("secretKeyConfigured", c.secretKey() != null);
    } else {
      storage.put("mode", "GLOBAL");
    }
    if (row.isPresent() && row.get().customIndex()) {
      var c = row.get().indexConfig();
      search.put("mode", "CUSTOM");
      search.put("backend", c.backend());
      search.put("endpoint", c.endpoint());
      search.put("prefix", c.prefix());
      search.put("username", c.username());
      search.put("apiKeyConfigured", c.apiKey() != null);
      search.put("passwordConfigured", c.password() != null);
      search.put("trustAllCertificates", c.trustAllCertificates());
    } else {
      search.put("mode", "GLOBAL");
    }
    return Map.of("storage", storage, "searchBackend", search);
  }

  /** Tests the stored dedicated configuration; a part that uses the global provider is not tested. */
  public Map<String, ConnectionTest> test(UUID crateId) {
    access.require(crateId, CrateMember.Role.OWNER);
    Map<String, ConnectionTest> result = new LinkedHashMap<>();
    settings.findById(crateId).ifPresent(row -> {
      if (row.customStorage()) result.put("storage", storageTester.test(row.s3Config()));
      if (row.customIndex()) result.put("searchBackend", searchTester.test(row.indexConfig()));
    });
    return result;
  }
}
