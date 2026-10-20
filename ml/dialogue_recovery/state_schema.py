"""
state_schema.py — 16-feature dialogue recovery state vector.

Version 1 state schema derived from the Cab Eye MatchGate and RecoveryLadder.
Feature order MUST be identical in Python training and Kotlin inference.

Evidence: Features 4–7 map to MatchGate.FailedGate enum values in stable order:
  0: TOO_SHORT, 1: ALL_STOPWORDS, 2: NO_CANDIDATE, 3: FRAGMENT_MATCH
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

import numpy as np

# ── Constants ───────────────────────────────────────────────────────────────
STATE_DIM = 16
MAX_ATTEMPT_NORM = 3.0
MAX_WORD_COUNT_NORM = 5.0

FEATURE_NAMES = [
    "attempt_number",           # 0
    "blank_transcript",         # 1
    "word_count",               # 2
    "best_match_score",         # 3
    "gate_too_short",           # 4
    "gate_all_stopwords",       # 5
    "gate_no_candidate",        # 6
    "gate_fragment_match",      # 7
    "candidate_exists",         # 8
    "prev_name_reason",         # 9
    "prev_offer_alternatives",  # 10
    "prev_offer_escapes",       # 11
    "prev_confirm_top_guess",   # 12
    "prev_offer_saved_places",  # 13
    "saved_places_available",   # 14
    "repeated_same_words",      # 15
]

assert len(FEATURE_NAMES) == STATE_DIM


@dataclass
class DialogueState:
    """Raw dialogue state before normalization."""

    attempt_number: int = 0
    blank_transcript: bool = False
    word_count: int = 0
    best_match_score: float = 0.0
    gate_too_short: bool = False
    gate_all_stopwords: bool = False
    gate_no_candidate: bool = False
    gate_fragment_match: bool = False
    candidate_exists: bool = False
    previous_action: Optional[int] = None  # 0–4 or None
    saved_places_available: bool = False
    repeated_same_words: bool = False

    def to_vector(self) -> np.ndarray:
        """Encode into the normalized 16-D float32 vector."""
        v = np.zeros(STATE_DIM, dtype=np.float32)

        # Index 0: attempt number — clip and normalize
        v[0] = min(self.attempt_number / MAX_ATTEMPT_NORM, 1.0)

        # Index 1: blank transcript
        v[1] = 1.0 if self.blank_transcript else 0.0

        # Index 2: word count — clip and normalize
        v[2] = min(self.word_count / MAX_WORD_COUNT_NORM, 1.0)

        # Index 3: best match score — assumed already in [0, 1]
        v[3] = float(np.clip(self.best_match_score, 0.0, 1.0))

        # Indices 4–7: MatchGate failure flags (stable order)
        v[4] = 1.0 if self.gate_too_short else 0.0
        v[5] = 1.0 if self.gate_all_stopwords else 0.0
        v[6] = 1.0 if self.gate_no_candidate else 0.0
        v[7] = 1.0 if self.gate_fragment_match else 0.0

        # Index 8: candidate exists
        v[8] = 1.0 if self.candidate_exists else 0.0

        # Indices 9–13: previous action one-hot (all zero if None)
        if self.previous_action is not None and 0 <= self.previous_action <= 4:
            v[9 + self.previous_action] = 1.0

        # Index 14: saved places available
        v[14] = 1.0 if self.saved_places_available else 0.0

        # Index 15: repeated same words
        v[15] = 1.0 if self.repeated_same_words else 0.0

        return v


def validate_state_vector(v: np.ndarray) -> bool:
    """Check that a state vector is well-formed."""
    if v.shape != (STATE_DIM,):
        return False
    if not np.all(np.isfinite(v)):
        return False
    if np.any(v < 0.0) or np.any(v > 1.0):
        return False
    return True
