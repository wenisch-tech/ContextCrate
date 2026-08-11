package tech.wenisch.contextcrate.answer;

import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.repository.RagSettingsRepository;

@Service
public class RagSettingsService {
  private final RagSettingsRepository settings; private final ContextCrateProperties.Answering config;
  public RagSettingsService(RagSettingsRepository settings, ContextCrateProperties properties){this.settings=settings;this.config=properties.answering();}
  @Transactional public RagSettings current(){return current(tech.wenisch.contextcrate.config.CrateContext.current());}
  @Transactional public RagSettings current(java.util.UUID crateId){return settings.findById(crateId).orElseGet(()->settings.save(new RagSettings(crateId,false,true,true,true,true,true,"revise-once",config.retrievalMode(),config.sourceLimit())));}
  @Transactional public RagSettings update(boolean strict,boolean history,boolean inline,boolean structured,boolean grading,boolean verification,String verificationAction,String mode,int limit){return update(tech.wenisch.contextcrate.config.CrateContext.current(),strict,history,inline,structured,grading,verification,verificationAction,mode,limit);}
  @Transactional public RagSettings update(java.util.UUID crateId,boolean strict,boolean history,boolean inline,boolean structured,boolean grading,boolean verification,String verificationAction,String mode,int limit){if(!Set.of("lexical","semantic","hybrid").contains(mode))throw new IllegalArgumentException("retrieval mode must be lexical, semantic, or hybrid");if(!Set.of("revise-once","block-answer","return-warning").contains(verificationAction))throw new IllegalArgumentException("answer verification action must be revise-once, block-answer, or return-warning");if(limit<1||limit>config.sourceLimit())throw new IllegalArgumentException("source limit must be between 1 and "+config.sourceLimit());var value=current(crateId);value.update(strict,history,inline,structured,grading,verification,verificationAction,mode,limit);return settings.save(value);}
}
