# Cab Eye — Preference-Memory Agent: architecture

**Type:** a memory-augmented, retrieval-and-scoring dialogue agent — *not* an LLM agent. It follows
the memory architecture described in the agent-memory literature (retrieve relevant episodes →
score → act → reflect on the outcome), implemented deterministically so every decision is fast
(on-device, no network wait), explainable (it can say *why*), and unit-testable. The existing
`LlmIntentResolver` interface is still unimplemented; nothing here depends on an LLM.

```
                ┌──────────────────────────── PERCEPTION ───────────────────────────┐
  rider voice → │ OnDeviceSpeechInput (STT, partials) → Classifier / IntentParser    │
                │ → GooglePlaceResolver / Gazetteer → MatchGate (confidence gate)    │
                └───────────────┬────────────────────────────────────────────────────┘
                                │ transcript, candidates, confidence, NO_MATCH
                ┌───────────────▼──────────── WORKING MEMORY ────────────────────────┐
                │ RiderViewModel: RiderState (screen/step), MicPurpose (which         │
                │ question is open), pendingMemory, rejectedMemoryKeys, lastPartial   │
                └───────────────┬────────────────────────────────────────────────────┘
                                │ query: words heard + moment (time, weekday, pickup)
┌───────────────────────────────▼──────── LONG-TERM MEMORY ────────────────────────────────┐
│ Episodic   TripRecord      every completed ride: place, spoken words, pickup, time        │
│ Semantic   VisitedPlace    per place: visits, hour histogram, weekday/weekend, aliases     │
│ Meta       MemoryStats     how the rider answered suggestions (accept / reject, by kind)   │
│ Store: backend MemoryService (JSON tables, per account)  ⇄  phone MemoryRepository cache   │
└───────────────────────────────┬──────────────────────────────────────────────────────────┘
                                │ retrieve
                ┌───────────────▼──────────── REASONING ─────────────────────────────┐
                │ PreferenceMemoryAgent                                               │
                │  proactive(): time-of-travel habit   share×support×recency×pickup×  │
                │               rider verdicts                                        │
                │  repair():    words→place  0.75·similarity + 0.15·time + 0.10·freq  │
                │               (TextSimilarity: Jaro-Winkler, token overlap,         │
                │                Indian-English phonetic key, spoken acronyms)         │
                │  direct():    learned alias / exact name / acronym (≥ 0.95)          │
                │  guessWhenUnheard(): habit for this moment when STT heard nothing   │
                └───────────────┬────────────────────────────────────────────────────┘
                                │ Suggestion(place, confidence, reason)
                ┌───────────────▼──────────── POLICY + GUARDRAILS ───────────────────┐
                │ Thresholds.from(stats)  (proactive 0.40–0.85, repair 0.50–0.85)     │
                │ never where you already are · 20-min cooldown · 2 declines = quiet  │
                │ rejected places excluded this booking · 3× rejected = dropped       │
                │ ASK, never act: yes still goes through the 5-second cancel window   │
                └───────────────┬────────────────────────────────────────────────────┘
                                │
                ┌───────────────▼──────────── ACTION ─────────────────────────────────┐
                │ Narrator asks, with the reason: "Going to PSG College, like most    │
                │ weekday mornings?" → mic opens → yes: book · no: previous step ·    │
                │ another place: book that                                            │
                └───────────────┬────────────────────────────────────────────────────┘
                                │ outcome
                ┌───────────────▼──────────── REFLECTION (Confidence Recalibration) ──┐
                │ reportOutcome → MemoryStats → next Thresholds; accepted repair →    │
                │ alias learned ("piece g" = PSG); completed ride → new episode       │
                └──────────────────────────────────────────────────────────────────────┘
```

## When the agent acts

| Trigger | Where in code | What it says |
|---|---|---|
| Signed in / app opened, idle | `offerProactive` | "It's 8 40 AM. Going to PSG College, like most weekday mornings?" |
| Places found nothing | `handleResolvedCandidates` → Reject | "Did you mean Brookefields Mall, where you've been 2 times?" |
| Places only half sure, memory surer | → NearMiss | same, instead of Places' guess |
| Recogniser failed (NO_MATCH/NO_SPEECH) | `handleSpeechError` | "I didn't catch that clearly. Did you mean …?" (once per booking) |
| Rider's own learned words | `resolveDestination` | books directly: "…one of your usual places. Say cancel to stop." |
| Only "college" / "mall" heard | `repair` generic branch | uses the time-of-travel habit to pick *their* college |

## Grounding

- *A User-driven Design Framework for Robotaxi* — pickup facilitation and accessible pickup needs
  (boarding code, driver verification, positioning presets — built earlier).
- *Memory for Autonomous LLM Agents* (survey) — episodic/semantic memory, retrieval-augmented
  decisions, reflective updating. Used here as the architectural pattern; the reasoning step is
  a transparent scoring function rather than an LLM.

## Files

`memory/PreferenceMemoryAgent.kt`, `memory/TextSimilarity.kt`, `memory/RiderMemory.kt`,
`memory/MemoryRepository.kt` (phone) · `memory/MemoryService.java`, `controller/MemoryController.java`
(backend) · tests: `MemoryAgentTest.kt`, `MemoryEndpointTest.java`.
