package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.config.CrateContext;

class ParallelLegsTest {
  private final UUID crateId = UUID.randomUUID();

  @Test
  void bothLegsRunAtTheSameTimeAndSeeTheCrateContext() throws Exception {
    // Each leg waits for the other one: this only completes when they really overlap.
    var meet = new CountDownLatch(2);
    var before = CrateContext.current();
    var keywordCrate = new AtomicReference<UUID>();
    var vectorCrate = new AtomicReference<UUID>();

    var legs = ParallelLegs.run(crateId,
        () -> { keywordCrate.set(CrateContext.current()); meet.countDown(); assertThat(meet.await(5, TimeUnit.SECONDS)).isTrue(); return List.of(); },
        () -> { vectorCrate.set(CrateContext.current()); meet.countDown(); assertThat(meet.await(5, TimeUnit.SECONDS)).isTrue(); return List.of(); });

    assertThat(legs.lexical()).isEmpty();
    assertThat(legs.semantic()).isEmpty();
    assertThat(keywordCrate.get()).isEqualTo(crateId);
    assertThat(vectorCrate.get()).isEqualTo(crateId);
    assertThat(CrateContext.current()).isEqualTo(before); // the caller's thread is untouched
  }

  @Test
  void aFailingLegRethrowsItsOwnException() {
    assertThatThrownBy(() -> ParallelLegs.run(crateId, List::of,
        () -> { throw new IllegalStateException("Semantic search requested but embeddings are unavailable"); }))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("embeddings are unavailable");
    assertThatThrownBy(() -> ParallelLegs.run(crateId, () -> { throw new IOException("down"); }, List::of))
        .isInstanceOf(IOException.class).hasMessage("down");
  }
}
