package tech.wenisch.contextcrate.domain;

public final class PipelineTypes {
  private PipelineTypes() {}

  public enum RunStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
  }

  public enum FrontierStatus {
    PENDING,
    QUEUED,
    FETCHED,
    EXCLUDED,
    FAILED
  }

  public enum WorkStage {
    FETCH,
    BROWSER_FETCH,
    PARSE,
    DISCOVERY,
    EXTRACT,
    INDEX
  }

  public enum ExtractionType {
    IP_ADDRESS,
    REGEX
  }

  public enum WorkStatus {
    PENDING,
    PROCESSING,
    RETRY_WAITING,
    COMPLETED,
    FAILED,
    CANCELLED,
    DEAD_LETTERED
  }

  public enum FetchOutcome {
    SUCCEEDED,
    HTTP_ERROR,
    BLOCKED,
    TOO_LARGE,
    UNSUPPORTED,
    FAILED
  }
}
