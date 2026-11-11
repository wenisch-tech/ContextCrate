package tech.wenisch.contextcrate.index;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tech.wenisch.contextcrate.config.CrateContext;
import tech.wenisch.contextcrate.index.SearchIndex.SearchHit;

/**
 * Runs the keyword leg and the vector leg of a search at the same time, so a fusion search takes as
 * long as the slower leg and not the sum. Both legs run with the crate context of the caller, which
 * the backends use for metrics. A failing leg rethrows its own exception, so callers keep their
 * existing error handling (for example "embeddings are unavailable").
 */
final class ParallelLegs {
  private ParallelLegs() {}

  record Legs(List<SearchHit> lexical, List<SearchHit> semantic) {}

  static Legs run(UUID crateId, Callable<List<SearchHit>> lexical, Callable<List<SearchHit>> semantic) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<List<SearchHit>> keyword = executor.submit(() -> inContext(crateId, lexical));
      Future<List<SearchHit>> vector = executor.submit(() -> inContext(crateId, semantic));
      try {
        return new Legs(keyword.get(), vector.get());
      } catch (ExecutionException e) {
        keyword.cancel(true);
        vector.cancel(true);
        if (e.getCause() instanceof Exception cause) throw cause;
        throw e;
      }
    }
  }

  private static List<SearchHit> inContext(UUID crateId, Callable<List<SearchHit>> leg) throws Exception {
    try (var ignored = CrateContext.use(crateId)) { return leg.call(); }
  }
}
