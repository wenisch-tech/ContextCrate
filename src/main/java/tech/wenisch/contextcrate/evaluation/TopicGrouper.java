package tech.wenisch.contextcrate.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.CrateContext;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;

/**
 * Groups topic wordings that mean the same. Order of preference: embeddings (similar vectors),
 * the judge model (asked to group a numbered list), and finally the exact text. A method that fails
 * hands over to the next one; topic text is never logged.
 */
@Component
public class TopicGrouper {
  private static final Logger log = LoggerFactory.getLogger(TopicGrouper.class);
  /** Cosine similarity from which two topics count as the same question. */
  static final double MERGE_THRESHOLD = 0.80;
  /** At most this many distinct topics are grouped; the rest stay on their own. */
  public static final int MAX_TOPICS = 300, MAX_JUDGE_TOPICS = 100;

  public record Grouping(String method, List<List<String>> clusters) {}

  private final EmbeddingProvider embeddings;
  private final JudgeClient judge;

  public TopicGrouper(EmbeddingProvider embeddings, JudgeClient judge) { this.embeddings = embeddings; this.judge = judge; }

  /**
   * @param topics distinct wordings, most frequent first
   * @return clusters of wordings; the first wording of a cluster is its most frequent one
   */
  public Grouping group(UUID crateId, List<String> topics) {
    if (topics.size() < 2) return new Grouping("text", topics.stream().map(t -> List.of(t)).toList());
    List<String> head = topics.size() > MAX_TOPICS ? topics.subList(0, MAX_TOPICS) : topics;
    List<List<String>> clusters = null;
    String method = "text";
    try (var ignored = CrateContext.use(crateId)) {
      if (embeddings.available()) {
        try {
          clusters = byEmbeddings(head);
          method = "embedding";
        } catch (Exception e) {
          log.warn("Grouping topics by embeddings failed. crateId={}: {}", crateId, e.getMessage());
        }
      }
      if (clusters == null && judge.available(crateId)) {
        try {
          clusters = byJudge(crateId, head);
          method = "judge";
        } catch (Exception e) {
          log.warn("Grouping topics by the judge failed. crateId={}: {}", crateId, e.getMessage());
        }
      }
    }
    if (clusters == null) clusters = byText(head);
    // Topics beyond the cap are not grouped; they only merge by exact text.
    if (topics.size() > head.size()) {
      clusters = new ArrayList<>(clusters);
      clusters.addAll(byText(topics.subList(head.size(), topics.size())));
    }
    return new Grouping(method, clusters);
  }

  static String key(String topic) { return topic.toLowerCase(Locale.ROOT).replaceAll("[\\s.?!]+$", ""); }

  static List<List<String>> byText(List<String> topics) {
    Map<String, List<String>> groups = new LinkedHashMap<>();
    for (String topic : topics) groups.computeIfAbsent(key(topic), k -> new ArrayList<>()).add(topic);
    return new ArrayList<>(groups.values());
  }

  /** Greedy: a topic joins the first cluster whose leader is similar enough, otherwise it leads a new one. */
  List<List<String>> byEmbeddings(List<String> topics) throws Exception {
    List<float[]> vectors = embeddings.embedQueries(topics);
    if (vectors.size() != topics.size()) throw new IllegalStateException("The embedding provider returned an unexpected number of vectors");
    List<List<String>> clusters = new ArrayList<>();
    List<float[]> leaders = new ArrayList<>();
    for (int i = 0; i < topics.size(); i++) {
      int target = -1;
      for (int c = 0; c < leaders.size() && target < 0; c++)
        if (RankingMetrics.cosine(leaders.get(c), vectors.get(i)) >= MERGE_THRESHOLD) target = c;
      if (target < 0) {
        clusters.add(new ArrayList<>(List.of(topics.get(i))));
        leaders.add(vectors.get(i));
      } else clusters.get(target).add(topics.get(i));
    }
    return clusters;
  }

  List<List<String>> byJudge(UUID crateId, List<String> topics) throws Exception {
    List<String> asked = topics.size() > MAX_JUDGE_TOPICS ? topics.subList(0, MAX_JUDGE_TOPICS) : topics;
    StringBuilder list = new StringBuilder();
    for (int i = 0; i < asked.size(); i++) list.append(i).append(": ").append(asked.get(i)).append('\n');
    JsonNode reply = JudgeProtocol.askJson(judge, crateId, JudgeProtocol.GROUP_TOPICS, JudgeProtocol.textInput("TOPICS", list.toString()));
    return fromIndexGroups(reply.path("groups"), topics, asked.size());
  }

  /** Repairs the judge's answer: unknown or repeated indices are ignored, forgotten ones stand alone. */
  static List<List<String>> fromIndexGroups(JsonNode groups, List<String> topics, int askedCount) {
    if (!groups.isArray()) throw new IllegalStateException("The judge reply has no groups");
    boolean[] used = new boolean[topics.size()];
    List<List<String>> clusters = new ArrayList<>();
    for (JsonNode group : groups) {
      if (!group.isArray()) continue;
      List<Integer> members = new ArrayList<>();
      for (JsonNode index : group) {
        if (!index.canConvertToInt()) continue;
        int i = index.asInt();
        if (i < 0 || i >= askedCount || used[i]) continue;
        used[i] = true;
        members.add(i);
      }
      if (members.isEmpty()) continue;
      Collections.sort(members); // lowest index = most frequent wording leads
      clusters.add(members.stream().map(topics::get).collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
    }
    for (int i = 0; i < topics.size(); i++) if (!used[i]) clusters.add(new ArrayList<>(List.of(topics.get(i))));
    return clusters;
  }
}
