package tech.wenisch.contextcrate.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.wenisch.contextcrate.config.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.*;

@Service
public class IndexRebuildService {
  private final NormalizedDocumentRepository documents;private final DocumentChunkRepository chunks;
  private final SearchIndex index;private final CrateRepository crates;
  private final CrateIndexGenerationRepository generations;private final RuntimeProviderSettings providers;
  private final TransactionTemplate transactions;
  private final ConcurrentHashMap<UUID,ReentrantLock> locks=new ConcurrentHashMap<>();
  public IndexRebuildService(NormalizedDocumentRepository documents,DocumentChunkRepository chunks,
      SearchIndex index,CrateRepository crates,CrateIndexGenerationRepository generations,
      RuntimeProviderSettings providers,PlatformTransactionManager transactionManager){
    this.documents=documents;this.chunks=chunks;this.index=index;this.crates=crates;
    this.generations=generations;this.providers=providers;
    this.transactions=new TransactionTemplate(transactionManager);
  }

  @Async public void rebuildAsync(UUID crateId){try{rebuild(crateId);}catch(Exception ignored){}}

  public long rebuild(UUID crateId)throws Exception{
    var lock=locks.computeIfAbsent(crateId,ignored->new ReentrantLock());
    lock.lock();
    try{return rebuildLocked(crateId);}
    finally {lock.unlock();if(!lock.hasQueuedThreads())locks.remove(crateId,lock);}
  }

  private long rebuildLocked(UUID crateId)throws Exception{
    Crate crate=crates.findById(crateId).orElseThrow();crate.requireActive();
    int generation=generations.findTopByCrateIdOrderByGenerationDesc(crateId)
        .map(g->g.getGeneration()+1).orElse(1);
    var embedding=providers.effectiveEmbedding(crateId);
    var record=generations.save(new CrateIndexGeneration(crateId,generation,
        fingerprint(embedding),embedding.provider()+":"+embedding.localModelId()+":"+embedding.openaiModel(),
        embedding.openaiDimensions()));
    long count=0;
    try(var ignored=CrateContext.use(crateId)){
      for(var document:documents.findByCrateIdAndCurrentVersionTrue(crateId)){
        index.upsertGeneration(crateId,generation,document,
            chunks.findByDocumentIdOrderByOrdinal(document.getId()));count++;
      }
      index.commitGeneration(crateId,generation);
      long finalCount=count;
      transactions.executeWithoutResult(status->{
        for(var document:documents.findByCrateIdAndCurrentVersionTrue(crateId)){
          document.indexed();documents.save(document);
        }
        for(var active:generations.findByCrateIdAndStatus(crateId,CrateIndexGeneration.Status.ACTIVE)){
          active.retire();generations.save(active);
        }
        record.activate(finalCount);generations.save(record);
        crate.activateGeneration(generation);crates.save(crate);
      });
      return count;
    }catch(Exception e){
      transactions.executeWithoutResult(status->{record.fail(e);generations.save(record);});
      try{index.deleteGeneration(crateId,generation);}catch(Exception ignored){}
      throw e;
    }
  }

  private static String fingerprint(Object value)throws Exception{
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
  }
}
