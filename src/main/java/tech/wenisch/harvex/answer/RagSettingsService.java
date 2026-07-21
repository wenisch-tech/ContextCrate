package tech.wenisch.harvex.answer;

import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.harvex.config.HarvexProperties;
import tech.wenisch.harvex.domain.RagSettings;
import tech.wenisch.harvex.repository.RagSettingsRepository;

@Service
public class RagSettingsService {
  private final RagSettingsRepository settings; private final HarvexProperties.Answering config;
  public RagSettingsService(RagSettingsRepository settings, HarvexProperties properties){this.settings=settings;this.config=properties.answering();}
  @Transactional public RagSettings current(){return settings.findById(1).orElseGet(()->settings.save(new RagSettings(false,true,true,true,config.retrievalMode(),config.sourceLimit())));}
  @Transactional public RagSettings update(boolean strict,boolean history,boolean inline,boolean structured,String mode,int limit){if(!Set.of("lexical","semantic","hybrid").contains(mode))throw new IllegalArgumentException("retrieval mode must be lexical, semantic, or hybrid");if(limit<1||limit>config.sourceLimit())throw new IllegalArgumentException("source limit must be between 1 and "+config.sourceLimit());var value=current();value.update(strict,history,inline,structured,mode,limit);return settings.save(value);}
}
