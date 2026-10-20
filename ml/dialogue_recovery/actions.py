"""
actions.py — Canonical recovery action enum and valid-action masking.

The five actions correspond to concrete dialogue behaviors in the Cab Eye
Android app. Action IDs are stable across training, export, and inference.
"""

from __future__ import annotations

from enum import IntEnum
from typing import List, Set

import numpy as np

from .state_schema import DialogueState


class RecoveryAction(IntEnum):
    """Canonical action IDs. Order matches the Q-network output layer."""

    NAME_REASON = 0           # Explain why match failed + city examples
    OFFER_ALTERNATIVES = 1    # Offer candidate list or landmarks
    OFFER_ESCAPES = 2         # Offer help, slow repeat, or call support
    CONFIRM_TOP_GUESS = 3     # Yes/no confirmation of top candidate
    OFFER_SAVED_PLACES = 4    # Offer a relevant saved destination

    @classmethod
    def count(cls) -> int:
        return len(cls)


ACTION_NAMES = [a.name for a in RecoveryAction]
ACTION_COUNT = RecoveryAction.count()

# ── Near-miss score threshold ───────────────────────────────────────────
# Matches Gazetteer.NEAR_MISS_FLOOR = 0.30f from the Android codebase.
NEAR_MISS_FLOOR = 0.30


def valid_actions(state: DialogueState) -> Set[RecoveryAction]:
    """
    Compute the set of actions whose preconditions are satisfied.

    Mirrors the masking rules from the DQN implementation spec:
    - NAME_REASON:         Always valid during recovery.
    - OFFER_ALTERNATIVES:  Valid if a candidate exists or word_count > 0.
    - OFFER_ESCAPES:       Always valid (deterministic fallback).
    - CONFIRM_TOP_GUESS:   Valid only if candidate exists AND score >= 0.30.
    - OFFER_SAVED_PLACES:  Valid only if saved places are available.
    """
    allowed: Set[RecoveryAction] = set()

    # Always available
    allowed.add(RecoveryAction.NAME_REASON)
    allowed.add(RecoveryAction.OFFER_ESCAPES)

    # Candidate-dependent
    if state.candidate_exists or state.word_count > 0:
        allowed.add(RecoveryAction.OFFER_ALTERNATIVES)

    if state.candidate_exists and state.best_match_score >= NEAR_MISS_FLOOR:
        allowed.add(RecoveryAction.CONFIRM_TOP_GUESS)

    # Saved-place-dependent
    if state.saved_places_available:
        allowed.add(RecoveryAction.OFFER_SAVED_PLACES)

    return allowed


def action_mask(state: DialogueState) -> np.ndarray:
    """
    Boolean mask: 1.0 for valid actions, 0.0 for invalid.

    Used to set invalid Q-values to -inf before argmax.
    """
    allowed = valid_actions(state)
    mask = np.zeros(ACTION_COUNT, dtype=np.float32)
    for a in allowed:
        mask[a.value] = 1.0
    return mask


def apply_mask(q_values: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Set invalid action Q-values to -inf. Returns a copy."""
    masked = q_values.copy()
    masked[mask == 0.0] = -np.inf
    return masked


def select_action(q_values: np.ndarray, mask: np.ndarray) -> int:
    """Greedy action selection over valid actions only."""
    masked = apply_mask(q_values, mask)
    if np.all(np.isinf(masked)):
        # Fallback: OFFER_ESCAPES is always valid
        return RecoveryAction.OFFER_ESCAPES.value
    return int(np.argmax(masked))
