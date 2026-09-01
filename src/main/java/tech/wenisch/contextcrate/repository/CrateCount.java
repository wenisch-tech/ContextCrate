package tech.wenisch.contextcrate.repository;

import java.util.UUID;

/** Aggregated per-crate row count, used to render crate overviews without an N+1 query. */
public interface CrateCount {
  UUID getCrateId();

  long getTotal();
}
