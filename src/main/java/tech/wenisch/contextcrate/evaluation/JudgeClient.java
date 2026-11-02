package tech.wenisch.contextcrate.evaluation;

import java.util.UUID;

/** One chat completion with the crate's judge model. Implementations must be deterministic (temperature 0). */
public interface JudgeClient {
  /** @return true when a judge model is configured for the crate */
  boolean available(UUID crateId);

  /** @return the model name, for the audit trail of a case */
  String model(UUID crateId);

  /** @throws Exception when the judge is not configured, unreachable or returns an error */
  String ask(UUID crateId, String system, String user) throws Exception;
}
