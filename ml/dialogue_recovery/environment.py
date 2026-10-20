"""
environment.py — Synthetic dialogue recovery environment.

Simulates the voice dialogue recovery scenarios that occur in the Cab Eye app
when speech/destination resolution fails. This is the training environment.

Evidence label: SIMULATED ENVIRONMENT — all results from this environment
must be clearly labeled as synthetic, never presented as real-rider data.

Scenario types:
  1. LOW_CONFIDENCE   — recognizer heard something, score below threshold
  2. AMBIGUOUS        — multiple candidates with similar scores
  3. NO_CANDIDATES    — nothing matched (stopwords, too short, etc.)
  4. SAVED_PLACE      — relevant saved place exists in rider history
  5. REPEATED_FAILURE — rider has already failed 2+ times in this episode
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Dict, List, Optional, Set, Tuple

import numpy as np

from .actions import ACTION_COUNT, RecoveryAction, action_mask, valid_actions
from .config import Config
from .reward import Outcome, StepResult, compute_reward
from .state_schema import STATE_DIM, DialogueState


class ScenarioType(Enum):
    LOW_CONFIDENCE = auto()
    AMBIGUOUS = auto()
    NO_CANDIDATES = auto()
    SAVED_PLACE = auto()
    REPEATED_FAILURE = auto()


@dataclass
class EnvState:
    """Internal environment state — richer than the agent's observation."""
    dialogue: DialogueState
    scenario: ScenarioType
    turn: int = 0
    resolved: bool = False
    last_outcome: Optional[Outcome] = None


class DialogueRecoveryEnv:
    """
    Gym-like synthetic environment for dialogue recovery training.

    Usage:
        env = DialogueRecoveryEnv(config, seed=42)
        state, mask = env.reset()
        while not done:
            action = agent.select_action(state, mask)
            next_state, reward, done, next_mask, info = env.step(action)
    """

    def __init__(self, config: Config, seed: int = 0):
        self.config = config
        self.rng = random.Random(seed)
        self.np_rng = np.random.RandomState(seed)

        self._state: Optional[EnvState] = None
        self._scenarios = list(ScenarioType)

    @property
    def state_dim(self) -> int:
        return STATE_DIM

    @property
    def action_count(self) -> int:
        return ACTION_COUNT

    def reset(self, scenario: Optional[ScenarioType] = None) -> Tuple[np.ndarray, np.ndarray]:
        """
        Start a new episode.

        Returns:
            (state_vector, action_mask)
        """
        if scenario is None:
            scenario = self.rng.choice(self._scenarios)

        dialogue = self._init_scenario(scenario)
        self._state = EnvState(dialogue=dialogue, scenario=scenario, turn=0)

        state_vec = dialogue.to_vector()
        mask = action_mask(dialogue)
        return state_vec, mask

    def step(self, action: int) -> Tuple[np.ndarray, float, bool, np.ndarray, Dict]:
        """
        Execute an action and return the transition.

        Returns:
            (next_state, reward, done, next_mask, info)
        """
        if self._state is None:
            raise RuntimeError("Call reset() before step().")

        env_state = self._state
        env_state.turn += 1
        dialogue = env_state.dialogue

        # Validate action
        allowed = valid_actions(dialogue)
        action_enum = RecoveryAction(action)

        if action_enum not in allowed:
            # Invalid action — penalize and force OFFER_ESCAPES
            action_enum = RecoveryAction.OFFER_ESCAPES

        # Simulate outcome of the action
        outcome = self._simulate_outcome(action_enum, env_state)

        # Check for max turns
        if outcome == Outcome.CONTINUE and env_state.turn >= self.config.max_steps_per_episode:
            outcome = Outcome.UNRESOLVED

        # Compute reward
        step_result = compute_reward(outcome, self.config)
        env_state.last_outcome = outcome

        # Build next state
        next_dialogue = self._next_dialogue_state(action_enum, outcome, env_state)
        env_state.dialogue = next_dialogue

        next_state = next_dialogue.to_vector()
        next_mask = action_mask(next_dialogue)

        info = {
            "scenario": env_state.scenario.name,
            "turn": env_state.turn,
            "outcome": outcome.name,
            "action": action_enum.name,
        }

        return next_state, step_result.reward, step_result.done, next_mask, info

    # ── Scenario initialization ──────────────────────────────────────────

    def _init_scenario(self, scenario: ScenarioType) -> DialogueState:
        """Create the initial DialogueState for a given scenario."""

        if scenario == ScenarioType.LOW_CONFIDENCE:
            return DialogueState(
                attempt_number=1,
                blank_transcript=False,
                word_count=self.rng.randint(1, 4),
                best_match_score=self.rng.uniform(0.15, 0.45),
                gate_too_short=False,
                gate_all_stopwords=False,
                gate_no_candidate=False,
                gate_fragment_match=self.rng.random() < 0.3,
                candidate_exists=True,
                saved_places_available=self.rng.random() < 0.3,
                repeated_same_words=False,
            )

        elif scenario == ScenarioType.AMBIGUOUS:
            return DialogueState(
                attempt_number=1,
                blank_transcript=False,
                word_count=self.rng.randint(2, 5),
                best_match_score=self.rng.uniform(0.35, 0.55),
                gate_too_short=False,
                gate_all_stopwords=False,
                gate_no_candidate=False,
                gate_fragment_match=False,
                candidate_exists=True,
                saved_places_available=self.rng.random() < 0.4,
                repeated_same_words=False,
            )

        elif scenario == ScenarioType.NO_CANDIDATES:
            gate_choice = self.rng.choice(["short", "stopwords", "no_match"])
            return DialogueState(
                attempt_number=1,
                blank_transcript=gate_choice == "short" and self.rng.random() < 0.3,
                word_count=0 if gate_choice == "short" else self.rng.randint(1, 3),
                best_match_score=0.0,
                gate_too_short=gate_choice == "short",
                gate_all_stopwords=gate_choice == "stopwords",
                gate_no_candidate=gate_choice == "no_match",
                gate_fragment_match=False,
                candidate_exists=False,
                saved_places_available=self.rng.random() < 0.2,
                repeated_same_words=False,
            )

        elif scenario == ScenarioType.SAVED_PLACE:
            return DialogueState(
                attempt_number=1,
                blank_transcript=False,
                word_count=self.rng.randint(1, 3),
                best_match_score=self.rng.uniform(0.10, 0.40),
                gate_too_short=False,
                gate_all_stopwords=False,
                gate_no_candidate=self.rng.random() < 0.4,
                gate_fragment_match=self.rng.random() < 0.2,
                candidate_exists=self.rng.random() < 0.5,
                saved_places_available=True,
                repeated_same_words=False,
            )

        elif scenario == ScenarioType.REPEATED_FAILURE:
            return DialogueState(
                attempt_number=self.rng.randint(2, 3),
                blank_transcript=self.rng.random() < 0.3,
                word_count=self.rng.randint(0, 3),
                best_match_score=self.rng.uniform(0.0, 0.35),
                gate_too_short=self.rng.random() < 0.3,
                gate_all_stopwords=self.rng.random() < 0.2,
                gate_no_candidate=self.rng.random() < 0.3,
                gate_fragment_match=self.rng.random() < 0.2,
                candidate_exists=self.rng.random() < 0.4,
                saved_places_available=self.rng.random() < 0.3,
                repeated_same_words=self.rng.random() < 0.5,
            )

        else:
            raise ValueError(f"Unknown scenario: {scenario}")

    # ── Outcome simulation ──────────────────────────────────────────────

    def _simulate_outcome(self, action: RecoveryAction, env_state: EnvState) -> Outcome:
        """
        Simulate what happens after the agent takes an action.

        Probabilities are documented assumptions — not real-world data.
        """
        dialogue = env_state.dialogue
        r = self.rng.random()

        # Chance of abandonment increases with turns
        abandon_prob = self.config.sim_abandon_prob * (1 + 0.1 * env_state.turn)
        if r < abandon_prob:
            return Outcome.ABANDON

        r = self.rng.random()  # fresh roll for the action outcome

        if action == RecoveryAction.NAME_REASON:
            # Explaining the failure — moderate chance of improved next attempt
            success_prob = self.config.sim_base_success_prob
            if dialogue.best_match_score > 0.3:
                success_prob += self.config.sim_confidence_boost
            if r < success_prob:
                return Outcome.CORRECT_CONFIRM
            return Outcome.CONTINUE

        elif action == RecoveryAction.OFFER_ALTERNATIVES:
            # Offering list of candidates — slightly better if candidates exist
            success_prob = self.config.sim_base_success_prob + 0.05
            if dialogue.candidate_exists:
                success_prob += self.config.sim_confidence_boost
            if r < success_prob:
                return Outcome.CORRECT_CONFIRM
            return Outcome.CONTINUE

        elif action == RecoveryAction.OFFER_ESCAPES:
            # Escape route — terminates but doesn't confirm
            if r < self.config.sim_escape_end_prob:
                return Outcome.ESCAPE
            return Outcome.CONTINUE

        elif action == RecoveryAction.CONFIRM_TOP_GUESS:
            # Explicit yes/no — higher success when score is decent
            base = self.config.sim_confirm_success_prob
            score_bonus = dialogue.best_match_score * 0.3
            if r < base + score_bonus:
                return Outcome.CORRECT_CONFIRM
            # Small chance of incorrect acceptance (safety test scenario)
            if r > 0.98:
                return Outcome.INCORRECT_DESTINATION
            return Outcome.CONTINUE

        elif action == RecoveryAction.OFFER_SAVED_PLACES:
            # Saved place — high success if available
            success_prob = self.config.sim_base_success_prob + self.config.sim_saved_place_boost
            if r < success_prob:
                return Outcome.CORRECT_CONFIRM
            return Outcome.CONTINUE

        return Outcome.CONTINUE

    # ── Next state construction ─────────────────────────────────────────

    def _next_dialogue_state(
        self, action: RecoveryAction, outcome: Outcome, env_state: EnvState
    ) -> DialogueState:
        """Build the dialogue state for the next step."""
        prev = env_state.dialogue

        # Simulate new speech attempt
        if outcome == Outcome.CONTINUE:
            new_word_count = max(0, prev.word_count + self.rng.randint(-1, 2))
            new_score = np.clip(
                prev.best_match_score + self.np_rng.uniform(-0.1, 0.15), 0.0, 1.0
            )
            new_candidate = new_score > 0.1 or prev.candidate_exists

            return DialogueState(
                attempt_number=prev.attempt_number + 1,
                blank_transcript=self.rng.random() < 0.1,
                word_count=new_word_count,
                best_match_score=float(new_score),
                gate_too_short=new_word_count == 0 and self.rng.random() < 0.3,
                gate_all_stopwords=self.rng.random() < 0.1,
                gate_no_candidate=not new_candidate,
                gate_fragment_match=self.rng.random() < 0.15,
                candidate_exists=new_candidate,
                previous_action=action.value,
                saved_places_available=prev.saved_places_available,
                repeated_same_words=self.rng.random() < 0.2,
            )

        # Terminal states return a zeroed-out state (won't be used for Q-learning)
        return DialogueState(previous_action=action.value)


# ── Baseline policies for comparison ─────────────────────────────────────

class FixedLadderPolicy:
    """
    Deterministic fixed recovery ladder — the current Cab Eye behavior.

    Rung 1 (attempt 1): NAME_REASON
    Rung 2 (attempt 2): OFFER_ALTERNATIVES (if available) else NAME_REASON
    Rung 3+ (attempt 3+): OFFER_ESCAPES
    """

    def select_action(self, state: DialogueState, mask: np.ndarray) -> int:
        if state.attempt_number <= 1:
            return RecoveryAction.NAME_REASON.value
        elif state.attempt_number == 2:
            if mask[RecoveryAction.OFFER_ALTERNATIVES.value] > 0:
                return RecoveryAction.OFFER_ALTERNATIVES.value
            return RecoveryAction.NAME_REASON.value
        else:
            return RecoveryAction.OFFER_ESCAPES.value


class RandomValidPolicy:
    """Randomly pick a valid action — diagnostic baseline."""

    def __init__(self, seed: int = 0):
        self.rng = random.Random(seed)

    def select_action(self, state: DialogueState, mask: np.ndarray) -> int:
        valid_indices = np.where(mask > 0)[0]
        if len(valid_indices) == 0:
            return RecoveryAction.OFFER_ESCAPES.value
        return int(self.rng.choice(valid_indices.tolist()))


class AlwaysEscalatePolicy:
    """Always offer escapes — worst-case diagnostic baseline."""

    def select_action(self, state: DialogueState, mask: np.ndarray) -> int:
        return RecoveryAction.OFFER_ESCAPES.value
