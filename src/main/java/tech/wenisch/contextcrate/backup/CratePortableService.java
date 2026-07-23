package tech.wenisch.contextcrate.backup;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;
import tech.wenisch.contextcrate.storage.*;

@Service
public class CratePortableService {
  private static final int SCHEMA=1;
  private final ObjectMapper mapper;private final CrateService crates;private final CrateAccessService access;
  private final CrawlJobRepository jobs;private final CrawlRunRepository runs;
  private final FrontierEntryRepository frontier;private final FetchRecordRepository fetches;
  private final NormalizedDocumentRepository documents;private final DocumentChunkRepository chunks;
  private final ExtractionRuleRepository rules;private final ExtractionResultRepository results;
  private final ArtifactStore artifacts;private final ConfigurationCodec codec;
  private final RagSettingsService rag;private final RuntimeProviderSettings providers;
  private final PipelineQueue queue;
  public CratePortableService(ObjectMapper mapper,CrateService crates,CrateAccessService access,
      CrawlJobRepository jobs,CrawlRunRepository runs,FrontierEntryRepository frontier,
      FetchRecordRepository fetches,NormalizedDocumentRepository documents,
      DocumentChunkRepository chunks,ExtractionRuleRepository rules,
      ExtractionResultRepository results,ArtifactStore artifacts,ConfigurationCodec codec,
      RagSettingsService rag,RuntimeProviderSettings providers,PipelineQueue queue){
    this.mapper=mapper;this.crates=crates;this.access=access;this.jobs=jobs;this.runs=runs;
    this.frontier=frontier;this.fetches=fetches;this.documents=documents;this.chunks=chunks;
    this.rules=rules;this.results=results;this.artifacts=artifacts;this.codec=codec;
    this.rag=rag;this.providers=providers;this.queue=queue;
  }

  public void exportTo(UUID crateId,Path target,boolean includeArtifacts)throws Exception{
    Crate crate=crates.require(crateId,CrateMember.Role.OWNER);
    Map<String,byte[]> entries=new LinkedHashMap<>();
    ObjectNode data=mapper.createObjectNode();
    data.set("crate",mapper.valueToTree(Map.of("name",crate.getName(),"description",
        crate.getDescription()==null?"":crate.getDescription())));
    data.set("jobs",mapper.valueToTree(jobs.findByCrateIdOrderByCreatedAtDesc(crateId).stream()
        .map(j->Map.of("id",j.getId(),"name",j.getName(),"enabled",j.isEnabled(),
            "configurationJson",codec.write(codec.read(j.getConfigurationJson()).withoutSecrets())))
        .toList()));
    data.set("runs",mapper.valueToTree(runs.findByCrateId(crateId)));
    data.set("frontier",mapper.valueToTree(frontier.findByCrateId(crateId)));
    data.set("fetches",mapper.valueToTree(fetches.findByCrateId(crateId)));
    data.set("documents",mapper.valueToTree(documents.findByCrateId(crateId)));
    data.set("chunks",mapper.valueToTree(chunks.findByCrateId(crateId)));
    data.set("rules",mapper.valueToTree(rules.findByCrateIdOrderByCreatedAtDesc(crateId)));
    data.set("results",mapper.valueToTree(results.findByCrateId(crateId)));
    data.set("rag",mapper.valueToTree(rag.current(crateId)));
    var embedding=providers.effectiveEmbedding(crateId);var answering=providers.effectiveAnswer(crateId);
    data.set("providers",mapper.valueToTree(new ProviderExport(embedding.enabled(),embedding.provider(),
        embedding.localModelId(),embedding.localRevision(),embedding.localDownloadUrl(),
        embedding.localCachePath().toString(),embedding.localModelPath()==null?null:embedding.localModelPath().toString(),
        embedding.openaiBaseUrl(),embedding.openaiModel(),embedding.openaiDimensions(),
        answering.enabled(),answering.baseUrl(),answering.model())));
    entries.put("data.json",mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
    if(includeArtifacts)for(var fetch:fetches.findByCrateId(crateId))
      if(fetch.getArtifactKey()!=null&&artifacts.exists(fetch.getArtifactKey()))
        try(var in=artifacts.open(fetch.getArtifactKey())){entries.put("artifacts/"+fetch.getId(),in.readAllBytes());}
    Map<String,String> checksums=new LinkedHashMap<>();
    entries.forEach((name,bytes)->checksums.put(name,Hashing.sha256(bytes)));
    Manifest manifest=new Manifest(SCHEMA,Instant.now(),crate.getName(),includeArtifacts,checksums);
    try(var zip=new ZipOutputStream(Files.newOutputStream(target))){
      write(zip,"manifest.json",mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
      for(var entry:entries.entrySet())write(zip,entry.getKey(),entry.getValue());
    }
  }

  public Manifest validate(Path bundle)throws Exception{return readBundle(bundle).manifest();}

  @Transactional
  public ImportResult importFrom(Path bundle)throws Exception{
    Bundle bundleData=readBundle(bundle);JsonNode data=mapper.readTree(bundleData.entries().get("data.json"));
    Crate crate=crates.create(text(data.path("crate"),"name")+" (imported)",
        nullable(data.path("crate"),"description"));
    UUID crateId=crate.getId();Map<UUID,UUID> jobIds=new HashMap<>(),runIds=new HashMap<>(),
        frontierIds=new HashMap<>(),fetchIds=new HashMap<>(),documentIds=new HashMap<>(),
        chunkIds=new HashMap<>(),ruleIds=new HashMap<>();
    for(JsonNode n:data.path("jobs")){
      UUID id=UUID.randomUUID();jobIds.put(uuid(n),id);
      var value=new CrawlJob(id,text(n,"name"),text(n,"configurationJson"));value.assignCrate(crateId);
      value.update(value.getName(),value.getConfigurationJson(),n.path("enabled").asBoolean());jobs.save(value);
    }
    for(JsonNode n:data.path("runs")){
      UUID id=UUID.randomUUID();runIds.put(uuid(n),id);
      var value=new CrawlRun(id,jobIds.get(UUID.fromString(text(n,"jobId"))),text(n,"configurationJson"));
      value.assignCrate(crateId);value.status(PipelineTypes.RunStatus.valueOf(text(n,"status")));runs.save(value);
    }
    for(JsonNode n:data.path("frontier")){
      UUID id=UUID.randomUUID();frontierIds.put(uuid(n),id);
      var value=new FrontierEntry(id,runIds.get(UUID.fromString(text(n,"runId"))),text(n,"url"),
          text(n,"canonicalUrl"),n.path("depth").asInt());value.assignCrate(crateId);
      value.status(PipelineTypes.FrontierStatus.valueOf(text(n,"status")));frontier.save(value);
    }
    for(JsonNode n:data.path("fetches")){
      UUID oldId=uuid(n),id=UUID.randomUUID();fetchIds.put(oldId,id);
      UUID runId=runIds.get(UUID.fromString(text(n,"runId")));
      var value=new FetchRecord(id,runId,frontierIds.get(UUID.fromString(text(n,"frontierEntryId"))),
          text(n,"requestedUrl"));value.assignCrate(crateId);
      byte[] body=bundleData.entries().get("artifacts/"+oldId);
      if(body!=null){
        String key="crates/"+crateId+"/runs/"+runId+"/"+id+".imported";
        var saved=artifacts.put(key,new ByteArrayInputStream(body),body.length+1L);
        value.success(nullable(n,"finalUrl"),n.path("statusCode").asInt(),nullable(n,"contentType"),
            nullable(n,"charset"),saved.key(),saved.sha256(),saved.length(),n.path("durationMs").asLong());
      }else value.failure(PipelineTypes.FetchOutcome.valueOf(text(n,"outcome")),
          nullable(n,"errorMessage"));
      fetches.save(value);
    }
    for(JsonNode n:data.path("documents")){
      UUID id=UUID.randomUUID();documentIds.put(uuid(n),id);
      var value=new NormalizedDocument(id,runIds.get(UUID.fromString(text(n,"runId"))),
          fetchIds.get(UUID.fromString(text(n,"fetchId"))),text(n,"canonicalUrl"),nullable(n,"title"),
          nullable(n,"language"),nullable(n,"description"),nullable(n,"author"),text(n,"body"),
          text(n,"contentHash"),text(n,"metadataJson"));value.assignCrate(crateId);documents.save(value);
    }
    for(JsonNode n:data.path("chunks")){
      UUID id=UUID.randomUUID();chunkIds.put(uuid(n),id);
      var value=new DocumentChunk(id,documentIds.get(UUID.fromString(text(n,"documentId"))),
          n.path("ordinal").asInt(),nullable(n,"heading"),text(n,"content"),text(n,"contentHash"));
      value.assignCrate(crateId);chunks.save(value);
    }
    for(JsonNode n:data.path("rules")){
      UUID id=UUID.randomUUID();ruleIds.put(uuid(n),id);
      var value=new ExtractionRule(id,text(n,"name"),
          PipelineTypes.ExtractionType.valueOf(text(n,"type")),nullable(n,"pattern"),
          n.path("enabled").asBoolean());value.assignCrate(crateId);rules.save(value);
    }
    for(JsonNode n:data.path("results")){
      var value=new ExtractionResult(UUID.randomUUID(),ruleIds.get(UUID.fromString(text(n,"ruleId"))),
          runIds.get(UUID.fromString(text(n,"runId"))),documentIds.get(UUID.fromString(text(n,"documentId"))),
          chunkIds.get(UUID.fromString(text(n,"chunkId"))),n.path("chunkOrdinal").asInt(),
          text(n,"matchedValue"),n.path("matchStart").asInt(),n.path("matchEnd").asInt(),
          nullable(n,"contextBefore"),nullable(n,"contextAfter"));value.assignCrate(crateId);results.save(value);
    }
    JsonNode r=data.path("rag");rag.update(crateId,r.path("strictGrounding").asBoolean(),
        r.path("allowClientHistory").asBoolean(),r.path("inlineCitations").asBoolean(),
        r.path("structuredSources").asBoolean(),text(r,"retrievalMode"),r.path("sourceLimit").asInt());
    JsonNode p=data.path("providers");providers.update(crateId,new RuntimeProviderSettings.ProviderForm(
        p.path("embeddingsEnabled").asBoolean(),text(p,"embeddingProvider"),nullable(p,"localModelId"),
        nullable(p,"localRevision"),nullable(p,"localDownloadUrl"),nullable(p,"localCachePath"),
        nullable(p,"localModelPath"),nullable(p,"openaiBaseUrl"),nullable(p,"openaiModel"),null,
        p.path("openaiDimensions").asInt(1536),p.path("answeringEnabled").asBoolean(),
        nullable(p,"answeringBaseUrl"),nullable(p,"answeringModel"),null));
    for(var document:documents.findByCrateId(crateId))queue.publish(PipelineMessage.create(crateId,
        PipelineTypes.WorkStage.INDEX,JobService.payload(crateId,document.getRunId(),document.getId()),
        document.getRunId(),crateId+":import-index:"+document.getId(),20));
    return new ImportResult(crateId,documents.findByCrateId(crateId).size());
  }

  private Bundle readBundle(Path path)throws Exception{
    Map<String,byte[]> entries=new LinkedHashMap<>();
    try(var zip=new ZipInputStream(Files.newInputStream(path))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
      if(entry.isDirectory())continue;String name=entry.getName();
      if(name.startsWith("/")||name.contains(".."))throw new IOException("Unsafe bundle entry");
      entries.put(name,zip.readAllBytes());
    }}
    Manifest manifest=mapper.readValue(required(entries,"manifest.json"),Manifest.class);
    if(manifest.schemaVersion()!=SCHEMA)throw new IOException("Unsupported crate export schema");
    for(var checksum:manifest.checksums().entrySet())
      if(!Hashing.sha256(required(entries,checksum.getKey())).equals(checksum.getValue()))
        throw new IOException("Checksum mismatch: "+checksum.getKey());
    return new Bundle(manifest,entries);
  }
  private static byte[] required(Map<String,byte[]> values,String key)throws IOException{
    byte[] value=values.get(key);if(value==null)throw new IOException("Missing "+key);return value;
  }
  private static void write(ZipOutputStream zip,String name,byte[] value)throws IOException{
    zip.putNextEntry(new ZipEntry(name));zip.write(value);zip.closeEntry();
  }
  private static UUID uuid(JsonNode n){return UUID.fromString(text(n,"id"));}
  private static String text(JsonNode n,String field){return n.path(field).asText();}
  private static String nullable(JsonNode n,String field){return n.hasNonNull(field)&&!n.path(field).asText().isBlank()?n.path(field).asText():null;}
  public record Manifest(int schemaVersion,Instant createdAt,String crateName,boolean includesArtifacts,Map<String,String> checksums){}
  private record Bundle(Manifest manifest,Map<String,byte[]> entries){}
  public record ImportResult(UUID crateId,long documents){}
  public record ProviderExport(boolean embeddingsEnabled,String embeddingProvider,String localModelId,
      String localRevision,String localDownloadUrl,String localCachePath,String localModelPath,
      String openaiBaseUrl,String openaiModel,int openaiDimensions,boolean answeringEnabled,
      String answeringBaseUrl,String answeringModel){}
}
