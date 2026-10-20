"""
evaluate.py — Evaluate DQN and baseline policies on held-out scenarios.

Runs greedy evaluation (epsilon=0) on a fixed set of scenarios with
deterministic seeds, so results are comparable across policies.
"""

from __future__ import annotations

import json
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

if __package__ in (None, ""):
    import sys
    from pathlib import Path
    _pkg_dir = Path(__file__).resolve().parent
    if str(_pkg_dir.parent) not in sys.path:
        sys.path.insert(0, str(_pkg_dir.parent))
    __package__ = _pkg_dir.name

import numpy as np

from .actions import ACTION_COUNT, ACTION_NAMES, RecoveryAction, action_mask
from .config import Config
from .dqn_model import DQNAgent
from .environment import (
    AlwaysEscalatePolicy,
    DialogueRecoveryEnv,
    FixedLadderPolicy,
    RandomValidPolicy,
    ScenarioType,
)
from .reward import Outcome
from .state_schema import DialogueState


@dataclass
class EvalResult:
    """Aggregated evaluation result for one policy."""

    policy_name: str = ""
    num_episodes: int = 0
    success_rate: float = 0.0
    incorrect_destination_rate: float = 0.0
    abandon_rate: float = 0.0
    escape_rate: float = 0.0
    unresolved_rate: float = 0.0
    mean_turns: float = 0.0
    median_turns: float = 0.0
    mean_return: float = 0.0
    std_return: float = 0.0
    action_distribution: Dict[str, int] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def evaluate_dqn(
    agent: DQNAgent,
    config: Config,
    num_episodes: int | None = None,
    seed: int | None = None,
    scenarios: List[ScenarioType] | None = None,
) -> EvalResult:
    """
    Evaluate the DQN agent greedily (no exploration) on fixed scenarios.

    Uses a separate evaluation seed and scenario set to prevent leakage.
    """
    n_eps = num_episodes or config.eval_episodes
    eval_seed = seed or config.eval_seed
    env = DialogueRecoveryEnv(config, seed=eval_seed)

    if scenarios is None:
        scenarios = list(ScenarioType)

    outcomes: List[Outcome] = []
    returns: List[float] = []
    turns: List[int] = []
    action_counts: Counter = Counter()

    for ep in range(n_eps):
        scenario = scenarios[ep % len(scenarios)]
        state, mask = env.reset(scenario=scenario)

        ep_return = 0.0
        ep_turns = 0
        done = False

        while not done:
            action = agent.select_action(state, mask, explore=False)
            action_counts[ACTION_NAMES[action]] += 1
            next_state, reward, done, next_mask, info = env.step(action)
            ep_return += reward
            ep_turns += 1
            state, mask = next_state, next_mask

        outcomes.append(Outcome[info["outcome"]])
        returns.append(ep_return)
        turns.append(ep_turns)

    return _aggregate(outcomes, returns, turns, action_counts, "DQN", n_eps)


def evaluate_baseline(
    policy,
    config: Config,
    policy_name: str,
    num_episodes: int | None = None,
    seed: int | None = None,
    scenarios: List[ScenarioType] | None = None,
) -> EvalResult:
    """Evaluate a baseline policy (FixedLadder, RandomValid, AlwaysEscalate)."""
    n_eps = num_episodes or config.eval_episodes
    eval_seed = seed or config.eval_seed
    env = DialogueRecoveryEnv(config, seed=eval_seed)

    if scenarios is None:
        scenarios = list(ScenarioType)

    outcomes: List[Outcome] = []
    returns: List[float] = []
    turns: List[int] = []
    action_counts: Counter = Counter()

    for ep in range(n_eps):
        scenario = scenarios[ep % len(scenarios)]
        state_vec, mask = env.reset(scenario=scenario)

        ep_return = 0.0
        ep_turns = 0
        done = False

        while not done:
            # Baseline policies need the DialogueState, not just the vector
            dialogue = env._state.dialogue
            action = policy.select_action(dialogue, mask)
            action_counts[ACTION_NAMES[action]] += 1
            next_state, reward, done, next_mask, info = env.step(action)
            ep_return += reward
            ep_turns += 1
            state_vec, mask = next_state, next_mask

        outcomes.append(Outcome[info["outcome"]])
        returns.append(ep_return)
        turns.append(ep_turns)

    return _aggregate(outcomes, returns, turns, action_counts, policy_name, n_eps)


def compare_policies(
    agent: DQNAgent,
    config: Config,
    num_episodes: int | None = None,
    seed: int | None = None,
) -> Dict[str, EvalResult]:
    """
    Compare DQN against all baseline policies on identical scenarios.

    Returns a dict: policy_name → EvalResult.
    """
    n_eps = num_episodes or config.eval_episodes
    eval_seed = seed or config.eval_seed

    results: Dict[str, EvalResult] = {}

    # DQN
    results["DQN"] = evaluate_dqn(agent, config, n_eps, eval_seed)

    # Baselines
    results["FixedLadder"] = evaluate_baseline(
        FixedLadderPolicy(), config, "FixedLadder", n_eps, eval_seed
    )
    results["RandomValid"] = evaluate_baseline(
        RandomValidPolicy(seed=eval_seed), config, "RandomValid", n_eps, eval_seed
    )
    results["AlwaysEscalate"] = evaluate_baseline(
        AlwaysEscalatePolicy(), config, "AlwaysEscalate", n_eps, eval_seed
    )

    return results


def save_comparison(results: Dict[str, EvalResult], path: str | Path) -> None:
    """Save comparison results as JSON."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {name: r.to_dict() for name, r in results.items()}
    with open(path, "w") as f:
        json.dump(data, f, indent=2)


# ── Internal helpers ─────────────────────────────────────────────────────

def _aggregate(
    outcomes: List[Outcome],
    returns: List[float],
    turns: List[int],
    action_counts: Counter,
    policy_name: str,
    n_eps: int,
) -> EvalResult:
    n = max(len(outcomes), 1)
    success = sum(1 for o in outcomes if o == Outcome.CORRECT_CONFIRM)
    incorrect = sum(1 for o in outcomes if o == Outcome.INCORRECT_DESTINATION)
    abandon = sum(1 for o in outcomes if o == Outcome.ABANDON)
    escape = sum(1 for o in outcomes if o == Outcome.ESCAPE)
    unresolved = sum(1 for o in outcomes if o == Outcome.UNRESOLVED)

    return EvalResult(
        policy_name=policy_name,
        num_episodes=n_eps,
        success_rate=round(success / n, 4),
        incorrect_destination_rate=round(incorrect / n, 4),
        abandon_rate=round(abandon / n, 4),
        escape_rate=round(escape / n, 4),
        unresolved_rate=round(unresolved / n, 4),
        mean_turns=round(float(np.mean(turns)), 3) if turns else 0.0,
        median_turns=round(float(np.median(turns)), 3) if turns else 0.0,
        mean_return=round(float(np.mean(returns)), 4) if returns else 0.0,
        std_return=round(float(np.std(returns)), 4) if returns else 0.0,
        action_distribution=dict(action_counts),
    )
