package tech.wenisch.contextcrate.domain;

/** Determines whether a job is started only by a user or also by its cron schedule. */
public enum IngestionJobMode {
  MANUAL, SCHEDULED
}
