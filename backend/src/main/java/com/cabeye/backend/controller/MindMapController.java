package com.cabeye.backend.controller;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.auth.CurrentAccount;
import com.cabeye.backend.mindmap.InterpretService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The rider's mind map lives on the phone; the server only offers to read a sentence.
 *
 * <pre>
 *   POST /me/mindmap/interpret   {"utterance":"…","known":["KG Clinic","Ravi's tea shop"]}
 *        → 200 {intent, subject, person, habit, meaning, relatesTo, sense, side, confidence}
 *        → 404 when no Grok key is configured (the app stays rules-only)
 *        → 429 over the hourly limit, 400 bad input, 503 the model did not answer in time
 * </pre>
 */
@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/me/mindmap")
public class MindMapController {

    private final InterpretService interpreter;

    public MindMapController(InterpretService interpreter) {
        this.interpreter = interpreter;
    }

    public record InterpretRequest(String utterance, List<String> known) {}

    @PostMapping("/interpret")
    public ResponseEntity<?> interpret(HttpServletRequest request, @RequestBody InterpretRequest body) {
        Account a = CurrentAccount.of(request).orElseThrow();
        if (!interpreter.enabled()) return ResponseEntity.notFound().build();
        InterpretService.Result r = interpreter.interpret(a.id, body.utterance(), body.known());
        if (r instanceof InterpretService.Result.Ok ok) return ResponseEntity.ok(ok.value());
        if (r instanceof InterpretService.Result.Limited) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "too many requests"));
        }
        String reason = ((InterpretService.Result.Failed) r).reason();
        return reason.startsWith("utterance")
                ? ResponseEntity.badRequest().body(Map.of("error", reason))
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", reason));
    }
}
