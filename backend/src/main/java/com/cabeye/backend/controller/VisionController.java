package com.cabeye.backend.controller;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.auth.CurrentAccount;
import com.cabeye.backend.vision.VisionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/**
 * "What's in front of me" for the rider's camera (ride and explore modules).
 *
 * <pre>
 *   GET  /me/vision                → {"enabled": true|false}
 *   POST /me/vision/describe       {"image":"<base64 JPEG>","mode":"DESCRIBE|FIND|READ","target":"door"}
 *        → 200 {summary, objects:[{label, box:[ymin,xmin,ymax,xmax], clock, distanceM}], hazards, text, found, source}
 *        → 404 no Gemini key configured (the phone uses its on-device detector)
 *        → 429 over the per-minute limit, 400 bad image, 503 Gemini did not answer
 * </pre>
 */
@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/me/vision")
public class VisionController {

    private final VisionService vision;

    public VisionController(VisionService vision) {
        this.vision = vision;
    }

    public record DescribeRequest(String image, String mode, String target) {}

    @GetMapping
    public Map<String, Object> status() {
        return Map.of("enabled", vision.enabled());
    }

    @PostMapping("/describe")
    public ResponseEntity<?> describe(HttpServletRequest request, @RequestBody DescribeRequest body) {
        Account a = CurrentAccount.of(request).orElseThrow();
        VisionService.Mode mode;
        try {
            mode = body.mode() == null ? VisionService.Mode.DESCRIBE
                    : VisionService.Mode.valueOf(body.mode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode must be DESCRIBE, FIND or READ"));
        }
        if (mode == VisionService.Mode.FIND && (body.target() == null || body.target().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "FIND needs a target"));
        }
        VisionService.Result r = vision.describe(a.id, body.image(), mode, body.target());
        if (r instanceof VisionService.Result.Ok ok) return ResponseEntity.ok(ok.scene());
        if (r instanceof VisionService.Result.Off) return ResponseEntity.notFound().build();
        if (r instanceof VisionService.Result.Limited) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "too many pictures, wait a moment"));
        }
        String reason = ((VisionService.Result.Failed) r).reason();
        return reason.startsWith("image")
                ? ResponseEntity.badRequest().body(Map.of("error", reason))
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", reason));
    }
}
