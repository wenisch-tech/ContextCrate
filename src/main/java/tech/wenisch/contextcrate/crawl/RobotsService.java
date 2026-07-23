package tech.wenisch.contextcrate.crawl;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class RobotsService {
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final Map<String, Rules> cache = new ConcurrentHashMap<>();

  public boolean allowed(String url, String userAgent) {
    URI uri = URI.create(url);
    String origin = uri.getScheme() + "://" + uri.getAuthority();
    Rules rules = cache.computeIfAbsent(origin, o -> load(o, userAgent));
    return rules.allowed(uri.getRawPath() == null ? "/" : uri.getRawPath());
  }

  private Rules load(String origin, String agent) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(origin + "/robots.txt"))
              .timeout(Duration.ofSeconds(8))
              .header("User-Agent", agent)
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) return Rules.ALLOW_ALL;
      return parse(response.body(), agent);
    } catch (Exception e) {
      return Rules.ALLOW_ALL;
    }
  }

  static Rules parse(String body, String agent) {
    List<String> disallow = new ArrayList<>(), allow = new ArrayList<>();
    boolean applies = false;
    for (String raw : body.split("\\R")) {
      String line = raw.replaceFirst("#.*$", "").trim();
      int colon = line.indexOf(':');
      if (colon < 0) continue;
      String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
          value = line.substring(colon + 1).trim();
      if (key.equals("user-agent"))
        applies =
            value.equals("*")
                || agent.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
      else if (applies && key.equals("disallow") && !value.isBlank()) disallow.add(value);
      else if (applies && key.equals("allow") && !value.isBlank()) allow.add(value);
    }
    return new Rules(disallow, allow);
  }

  record Rules(List<String> disallow, List<String> allow) {
    static final Rules ALLOW_ALL = new Rules(List.of(), List.of());

    boolean allowed(String path) {
      int allowLen =
          allow.stream().filter(path::startsWith).mapToInt(String::length).max().orElse(-1);
      int denyLen =
          disallow.stream().filter(path::startsWith).mapToInt(String::length).max().orElse(-1);
      return allowLen >= denyLen;
    }
  }
}
