package tech.wenisch.contextcrate.domain;

public enum ConnectorType {
  HTTPS,
  GIT,
  /** Internal: synthetic documents made from rated answers; never created or shown by users. */
  FEEDBACK
}
