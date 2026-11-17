package tech.wenisch.contextcrate.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.feedback.FeedbackService;

/**
 * The public rating page behind the link at the end of an answer. The unguessable token is the only credential;
 * it shows nothing but the question and the answer the visitor already received. Unknown and expired tokens look the same.
 */
@Controller
@RequestMapping("/feedback/{token}")
public class FeedbackPageController {
  private static final int EXCERPT = 1500;
  private final FeedbackService feedback;

  public FeedbackPageController(FeedbackService feedback) { this.feedback = feedback; }

  @GetMapping
  public String form(@PathVariable String token, @RequestParam(required = false) Integer relevance, @RequestParam(required = false) Integer quality, Model model, jakarta.servlet.http.HttpServletResponse response) {
    var row = feedback.find(token);
    if (row.isEmpty()) return notFound(response);
    var f = row.get();
    model.addAttribute("token", token);
    model.addAttribute("question", f.getQuestion());
    model.addAttribute("answer", f.getAnswer().length() > EXCERPT ? f.getAnswer().substring(0, EXCERPT) + " …" : f.getAnswer());
    model.addAttribute("relevance", relevance != null && relevance >= 1 && relevance <= 5 ? relevance : f.getRelevance() == null ? null : (int) f.getRelevance());
    model.addAttribute("quality", quality != null && quality >= 1 && quality <= 5 ? quality : f.getQuality() == null ? null : (int) f.getQuality());
    model.addAttribute("comment", f.getComment());
    model.addAttribute("done", false);
    return "feedback";
  }

  @PostMapping
  public String submit(@PathVariable String token, @RequestParam(required = false) Integer relevance, @RequestParam(required = false) Integer quality,
      @RequestParam(required = false) String comment, Model model, jakarta.servlet.http.HttpServletResponse response) {
    try {
      if (relevance == null && quality == null && (comment == null || comment.isBlank())) throw new IllegalArgumentException("Please choose a rating.");
      var saved = feedback.rate(token, relevance, quality, comment);
      if (saved.isEmpty()) return notFound(response);
    } catch (IllegalArgumentException e) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      return form(token, relevance, quality, withError(model, e.getMessage()), response);
    }
    model.addAttribute("done", true);
    return "feedback";
  }

  private static Model withError(Model model, String message) { model.addAttribute("error", message); return model; }

  private static String notFound(jakarta.servlet.http.HttpServletResponse response) {
    response.setStatus(HttpStatus.NOT_FOUND.value());
    return "feedback-gone";
  }
}
