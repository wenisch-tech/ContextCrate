package tech.wenisch.contextcrate.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.AnswerFeedbackSource;

public interface AnswerFeedbackSourceRepository extends JpaRepository<AnswerFeedbackSource, UUID> {
  List<AnswerFeedbackSource> findByFeedbackIdIn(Collection<UUID> feedbackIds);
  List<AnswerFeedbackSource> findByFeedbackId(UUID feedbackId);
  void deleteByFeedbackId(UUID feedbackId);
}
