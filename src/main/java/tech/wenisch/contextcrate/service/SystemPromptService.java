package tech.wenisch.contextcrate.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.answer.SystemPrompts;
import tech.wenisch.contextcrate.domain.AuditLog;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.repository.AuditLogRepository;

/** A crate's own system prompt: who may edit it, what the owner sees, and what the model finally receives. */
@Service
public class SystemPromptService {
  private final RagSettingsService rag;
  private final SystemSettingsService system;
  private final CrateAccessService access;
  private final AuditLogRepository audits;
  private final AnswerService answers;

  public SystemPromptService(RagSettingsService rag, SystemSettingsService system, CrateAccessService access, AuditLogRepository audits, AnswerService answers) {
    this.rag = rag; this.system = system; this.access = access; this.audits = audits; this.answers = answers;
  }

  /**
   * @param systemPrompt the crate's prompt as stored (it is kept while the feature is switched off)
   * @param allowed whether the administrator currently allows crate system prompts
   * @param installationPrompt the administrator's installation-wide prompt, shown so owners do not contradict it
   * @param preview the full system prompt the model receives with the current settings
   */
  public record View(String systemPrompt, boolean allowed, String installationPrompt, String preview, int maxLength) {}

  public View view(UUID crateId) {
    access.require(crateId, CrateMember.Role.OWNER);
    var settings = system.current();
    return new View(rag.current(crateId).getSystemPrompt(), settings.isAllowCrateSystemPrompts(), SystemPrompts.clean(settings.getGlobalSystemPrompt()),
        answers.previewSystemPrompt(crateId), SystemPrompts.MAX_LENGTH);
  }

  /** @throws IllegalStateException when the administrator has not allowed crate system prompts; @throws IllegalArgumentException when it is too long */
  @Transactional
  public View update(UUID crateId, String prompt) {
    access.requireMutable(crateId, CrateMember.Role.OWNER);
    if (!system.current().isAllowCrateSystemPrompts())
      throw new IllegalStateException("The administrator has not enabled system prompts for crates");
    String cleaned = SystemPrompts.validated(prompt);
    rag.updateSystemPrompt(crateId, cleaned);
    // The text itself never goes into the audit log, only that it changed and how long it is.
    audits.save(new AuditLog(crateId, access.currentUser().getEmail(), "SYSTEM_PROMPT_UPDATED", crateId.toString(), "length=" + (cleaned == null ? 0 : cleaned.length())));
    return view(crateId);
  }
}
