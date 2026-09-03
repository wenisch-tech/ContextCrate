package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class IngestionScheduleTest {
  @Test
  void acceptsFiveFieldCronAndMatchesInTheConfiguredZone() {
    var berlin = ZonedDateTime.of(2026, 9, 4, 10, 5, 0, 0, ZoneId.of("Europe/Berlin"));

    assertThat(IngestionSchedule.matches("*/5 * * * *", berlin)).isTrue();
    assertThat(IngestionSchedule.matches("*/5 * * * *", berlin.plusMinutes(1))).isFalse();
  }

  @Test
  void rejectsNonFiveFieldExpressions() {
    assertThatThrownBy(() -> IngestionSchedule.parse("0 */5 * * * *"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("five fields");
  }
}
