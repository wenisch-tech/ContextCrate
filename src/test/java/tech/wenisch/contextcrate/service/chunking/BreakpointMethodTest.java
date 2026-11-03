package tech.wenisch.contextcrate.service.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class BreakpointMethodTest {
  private static final double[] D = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

  @Test
  void percentileInterpolatesBetweenRanks() {
    // rank = 0.95 * 9 = 8.55 -> 9 + 0.55 * (10 - 9)
    assertThat(BreakpointMethod.PERCENTILE.threshold(D, 95)).isCloseTo(9.55, within(1e-9));
    assertThat(BreakpointMethod.PERCENTILE.threshold(D, 50)).isCloseTo(5.5, within(1e-9));
  }

  @Test
  void standardDeviationUsesMeanPlusMultiplier() {
    // mean 5.5, population variance 8.25
    assertThat(BreakpointMethod.STDDEV.threshold(D, 1)).isCloseTo(5.5 + Math.sqrt(8.25), within(1e-9));
    assertThat(BreakpointMethod.STDDEV.threshold(D, 0)).isCloseTo(5.5, within(1e-9));
  }

  @Test
  void interquartileRangeAddsMultipleOfIqrToQ3() {
    // Q1 = 3.25, Q3 = 7.75
    assertThat(BreakpointMethod.IQR.threshold(D, 1.5)).isCloseTo(7.75 + 1.5 * 4.5, within(1e-9));
  }

  @Test
  void identicalDistancesNeverExceedTheirThreshold() {
    double[] same = {0.2, 0.2, 0.2, 0.2};
    for (var method : BreakpointMethod.values())
      assertThat(0.2).isLessThanOrEqualTo(method.threshold(same, method == BreakpointMethod.PERCENTILE ? 95 : 1) + 1e-12);
  }

  @Test
  void methodLookupFallsBackToPercentile() {
    assertThat(BreakpointMethod.of("stddev")).isEqualTo(BreakpointMethod.STDDEV);
    assertThat(BreakpointMethod.of("IQR")).isEqualTo(BreakpointMethod.IQR);
    assertThat(BreakpointMethod.of("nonsense")).isEqualTo(BreakpointMethod.PERCENTILE);
  }
}
