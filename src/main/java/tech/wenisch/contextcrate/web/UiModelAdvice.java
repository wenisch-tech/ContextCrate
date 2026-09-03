package tech.wenisch.contextcrate.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class UiModelAdvice {
  private final String applicationVersion;

  UiModelAdvice(@Value("${contextcrate.version:0.1.0-SNAPSHOT}") String applicationVersion) {
    this.applicationVersion = applicationVersion;
  }

  @ModelAttribute("applicationVersion")
  String applicationVersion() {
    return applicationVersion;
  }

  @ModelAttribute("currentPath")
  String currentPath(HttpServletRequest request) {
    String value = request.getRequestURI();
    return value.endsWith("/") && value.length() > 1 ? value.substring(0, value.length() - 1) : value;
  }
}
