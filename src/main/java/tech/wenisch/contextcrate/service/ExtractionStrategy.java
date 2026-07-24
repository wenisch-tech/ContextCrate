package tech.wenisch.contextcrate.service;

import java.util.List;
import tech.wenisch.contextcrate.domain.ExtractionRule;

public interface ExtractionStrategy {
  boolean supports(ExtractionRule rule);

  void validate(ExtractionRule rule);

  List<ExtractionMatch> extract(ExtractionRule rule, String text);
}