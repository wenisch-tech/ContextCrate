package tech.wenisch.contextcrate.web;

import org.springframework.ui.Model;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.SystemSetting;
import tech.wenisch.contextcrate.util.InsecureSsl;

/** The model attributes of the storage and search choice (create and duplicate dialog). */
final class BackendDialogModel {
  private BackendDialogModel() {}

  /** @param policy the installation's settings, or {@code null}; @param properties the configuration, or {@code null} */
  static void add(Model model, SystemSetting policy, ContextCrateProperties properties) {
    model.addAttribute("allowCustomStorage", policy != null && policy.isAllowCustomStorage());
    model.addAttribute("allowCustomSearchBackend", policy != null && policy.isAllowCustomSearchBackend());
    model.addAttribute("allowUnsafeBackends", policy != null && policy.isAllowUnsafeBackends());
    model.addAttribute("globalTrustAll", InsecureSsl.globalTrustAll());
    model.addAttribute("globalStorage", properties == null || !"s3".equals(properties.artifacts().backend()) ? "local file storage" : "S3 storage");
    model.addAttribute("globalSearch", properties == null ? "search index" : switch (properties.index().backend()) {
      case "opensearch" -> "OpenSearch cluster";
      case "elasticsearch" -> "Elasticsearch cluster";
      case "qdrant" -> "Qdrant server";
      case "pgvector" -> "PostgreSQL pgvector index";
      default -> "built-in Lucene index";
    });
  }
}
