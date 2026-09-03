package tech.wenisch.contextcrate.service;

import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

/** Five-field Unix cron adapter backed by Spring's tested cron evaluator. */
public final class IngestionSchedule {
  private IngestionSchedule() {}

  public static CronExpression parse(String expression) {
    if (expression == null || expression.isBlank())
      throw new IllegalArgumentException("A cron expression is required for scheduled jobs");
    String value = expression.trim().replaceAll("\\s+", " ");
    if (value.split(" ").length != 5)
      throw new IllegalArgumentException("Cron expressions must have five fields: minute hour day month weekday");
    try {
      return CronExpression.parse("0 " + value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid five-field cron expression: " + expression, e);
    }
  }

  public static boolean matches(String expression, ZonedDateTime time) {
    CronExpression cron = parse(expression);
    ZonedDateTime previousMinute = time.withSecond(0).withNano(0).minusMinutes(1);
    ZonedDateTime next = cron.next(previousMinute);
    return next != null && next.toInstant().equals(time.withSecond(0).withNano(0).toInstant());
  }
}
