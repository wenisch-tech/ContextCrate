package tech.wenisch.contextcrate.crawl;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class HostPoliteness {
  private final ConcurrentHashMap<String, AtomicLong> next = new ConcurrentHashMap<>();

  public void await(String url, long delay) throws InterruptedException {
    String host = URI.create(url).getHost();
    AtomicLong value = next.computeIfAbsent(host, h -> new AtomicLong());
    long now = System.currentTimeMillis(),
        slot = value.getAndUpdate(previous -> Math.max(previous, now) + delay),
        wait = Math.max(0, slot - now);
    if (wait > 0) Thread.sleep(wait);
  }
}
