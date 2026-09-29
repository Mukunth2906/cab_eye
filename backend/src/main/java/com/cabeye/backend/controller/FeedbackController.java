package com.cabeye.backend.controller;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.auth.CurrentAccount;
import com.cabeye.backend.feedback.FeedbackRecord;
import com.cabeye.backend.feedback.FeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * <pre>
 *   POST /rides/{rideId}/feedback   {"rating":4,"category":"SAFETY","text":"…"}   any part optional
 *   GET  /me/feedback               the signed-in rider's own feedback
 * </pre>
 * Guests may give feedback too — a guest's safety report matters as much as anyone's.
 */
@RestController
@CrossOrigin(originPatterns = "*")
public class FeedbackController {

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping("/rides/{rideId}/feedback")
    public ResponseEntity<?> submit(@PathVariable String rideId,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        String riderId = CurrentAccount.of(request).map(a -> a.id).orElse(null);
        Integer rating = null;
        Object r = body.get("rating");
        if (r instanceof Number n) rating = n.intValue();
        else if (r != null && !String.valueOf(r).isBlank()) {
            try {
                rating = Integer.parseInt(String.valueOf(r).trim());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "A rating is from one to five."));
            }
        }
        try {
            FeedbackRecord saved = feedback.submit(rideId, riderId, rating,
                    body.get("category") == null ? null : String.valueOf(body.get("category")),
                    body.get("text") == null ? null : String.valueOf(body.get("text")));
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/me/feedback")
    public List<FeedbackRecord> mine(HttpServletRequest request) {
        Account a = CurrentAccount.of(request).orElseThrow();
        return feedback.forRider(a.id);
    }
}
