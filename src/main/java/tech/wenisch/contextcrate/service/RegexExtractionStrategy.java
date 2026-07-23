package tech.wenisch.contextcrate.service;

import static tech.wenisch.contextcrate.domain.PipelineTypes.ExtractionType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.domain.ExtractionRule;

@Component
public class RegexExtractionStrategy implements ExtractionStrategy {
  private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

  @Override
  public boolean supports(ExtractionRule rule) {
    return rule.getType() == ExtractionType.REGEX;
  }

  @Override
  public void validate(ExtractionRule rule) {
    if (rule.getPattern() == null || rule.getPattern().isBlank())
      throw new IllegalArgumentException("pattern is required for regex extraction rules");
    pattern(rule);
  }

  @Override
  public List<ExtractionMatch> extract(ExtractionRule rule, String text) {
    var matcher = pattern(rule).matcher(text);
    List<ExtractionMatch> matches = new ArrayList<>();
    while (matcher.find())
      matches.add(new ExtractionMatch(matcher.group(), matcher.start(), matcher.end()));
    return matches;
  }

  private Pattern pattern(ExtractionRule rule) {
    return patterns.computeIfAbsent(rule.getId() + ":" + rule.getPattern(), k -> Pattern.compile(rule.getPattern()));
  }
}