"""
reward.py — Reward design and episode termination.

Reward values are experiment starting points from the specification.
An episode starts when the app enters recovery after an unresolved speech
result and ends on confirmation, abandonment, retry limit, or escape.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto

from .config import Config


class Outcome(Enum):
    """Terminal episode outcomes."""
    CORRECT_CONFIRM = auto()       # Rider explicitly confirmed correct destination
    INCORRECT_DESTINATION = auto() # Wrong destination accepted — safety failure
    ABANDON = auto()               # Rider abandoned / gave up
    ESCAPE = auto()                # Rider took an escape route (help/support)
    UNRESOLVED = auto()            # Hit max turns without resolution
    CONTINUE = auto()              # Episode not yet terminal


@dataclass
class StepResult:
    """Outcome of a single recovery step."""
    outcome: Outcome
    reward: float
    done: bool


def compute_reward(outcome: Outcome, config: Config) -> StepResult:
    """
    Map an outcome to its reward and done flag.

    Uses reward weights from the config for reproducibility.
    """
    if outcome == Outcome.CORRECT_CONFIRM:
        return StepResult(outcome=outcome, reward=config.reward_correct_confirm, done=True)

    elif outcome == Outcome.INCORRECT_DESTINATION:
        return StepResult(outcome=outcome, reward=config.reward_incorrect_destination, done=True)

    elif outcome == Outcome.ABANDON:
        return StepResult(outcome=outcome, reward=config.reward_abandon, done=True)

    elif outcome == Outcome.ESCAPE:
        # Escape is terminal — rider chose an alternative recovery path
        return StepResult(outcome=outcome, reward=config.reward_per_turn, done=True)

    elif outcome == Outcome.UNRESOLVED:
        return StepResult(outcome=outcome, reward=config.reward_unresolved_attempt, done=True)

    elif outcome == Outcome.CONTINUE:
        return StepResult(outcome=outcome, reward=config.reward_per_turn, done=False)

    else:
        raise ValueError(f"Unknown outcome: {outcome}")
