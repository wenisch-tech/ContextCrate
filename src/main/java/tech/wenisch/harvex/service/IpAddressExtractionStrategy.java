package tech.wenisch.harvex.service;

import static tech.wenisch.harvex.domain.PipelineTypes.ExtractionType;

import java.net.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.domain.ExtractionRule;

@Component
public class IpAddressExtractionStrategy implements ExtractionStrategy {
  private static final Pattern CANDIDATE =
      Pattern.compile(
        "(?i)(?<![\\p{Alnum}:.])(?:\\d{1,3}(?:\\.\\d{1,3}){3})(?![\\p{Alnum}.])|(?<![\\p{Alnum}:])(?:[0-9a-f]{0,4}:[0-9a-f:.]*[0-9a-f])(?![\\p{Alnum}:])");

  @Override
  public boolean supports(ExtractionRule rule) {
    return rule.getType() == ExtractionType.IP_ADDRESS;
  }

  @Override
  public void validate(ExtractionRule rule) {}

  @Override
  public List<ExtractionMatch> extract(ExtractionRule rule, String text) {
    List<ExtractionMatch> matches = new ArrayList<>();
    var matcher = CANDIDATE.matcher(text);
    while (matcher.find()) {
      String value = matcher.group();
      if (valid(value)) matches.add(new ExtractionMatch(value, matcher.start(), matcher.end()));
    }
    return matches;
  }

  private static boolean valid(String value) {
    if (value.indexOf('.') >= 0 && value.indexOf(':') < 0) return validIpv4(value);
    if (value.indexOf(':') >= 0) return validIpv6(value);
    return false;
  }

  private static boolean validIpv4(String value) {
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) return false;
    for (String part : parts) {
      if (part.isBlank() || part.length() > 3) return false;
      for (int i = 0; i < part.length(); i++) if (!Character.isDigit(part.charAt(i))) return false;
      int number = Integer.parseInt(part);
      if (number > 255) return false;
    }
    return true;
  }

  private static boolean validIpv6(String value) {
    try {
      return InetAddress.getByName(value) instanceof Inet6Address;
    } catch (Exception e) {
      return false;
    }
  }
}