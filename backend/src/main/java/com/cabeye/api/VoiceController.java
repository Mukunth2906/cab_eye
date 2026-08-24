package com.cabeye.api;

import com.cabeye.api.dto.ClarifyRequest;
import com.cabeye.api.dto.ClarifyResponse;
import com.cabeye.api.dto.InterpretRequest;
import com.cabeye.api.dto.InterpretResponse;
import com.cabeye.nlu.NluService;
import com.cabeye.nlu.Place;
import com.cabeye.nlu.Resolution;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The endpoint the rider app actually depends on.
 *
 * <p>Two operations, matching the two turns a booking can take: work out
 * what a sentence meant, and — only when the first one asked a question —
 * work out which answer came back.
 */
@RestController
@RequestMapping("/api/v1/voice")
public class VoiceController {

    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);

    private final NluService nlu;

    public VoiceController(NluService nlu) {
        this.nlu = nlu;
    }

    @PostMapping("/interpret")
    public InterpretResponse interpret(@Valid @RequestBody InterpretRequest req) {
        long start = System.nanoTime();
        Resolution r = nlu.resolve(req.text());
        long micros = (System.nanoTime() - start) / 1000;

        // Logged at info because the fast-path fraction and the ask/book
        // split are the two numbers this prototype exists to produce.
        log.info("interpret session={} lang={} fast={} decision={} query=\"{}\" gap={} div={}km {}us",
                req.sessionId(), req.langOrDefault(), r.fastPath(),
                !r.resolved() ? "unresolved" : r.needsClarify() ? "ask" : "book",
                r.query(), String.format("%.2f", r.gap()),
                String.format("%.1f", r.divergenceKm()), micros);

        return InterpretResponse.from(r);
    }

    /**
     * "East." — which of the two places the rider just picked.
     *
     * <p>The candidates come back from the client rather than being held
     * in server state. That keeps this service stateless, and it means a
     * dropped connection between the question and the answer costs the
     * user nothing.
     */
    @PostMapping("/clarify")
    public ClarifyResponse clarify(@Valid @RequestBody ClarifyRequest req) {
        List<Place> candidates = req.toPlaces();
        if (candidates.size() < 2) {
            return ClarifyResponse.unresolved("I need two options to choose between.");
        }

        int index = nlu.pickCandidate(req.answer(), candidates);
        if (index < 0) {
            log.info("clarify session={} answer=\"{}\" -> undecided", req.sessionId(), req.answer());
            return ClarifyResponse.unresolved(
                    "Sorry. " + candidates.get(0).name() + ", or " + candidates.get(1).name() + "?");
        }

        Place picked = candidates.get(index);
        log.info("clarify session={} answer=\"{}\" -> {}", req.sessionId(), req.answer(), picked.name());
        return ClarifyResponse.picked(index, picked);
    }
}
