"""
config.py — Training configuration with YAML serialization.

All hyperparameters, paths, and seeds live here so every run is reproducible.
The resolved config is saved alongside checkpoints and metrics for each run.
"""

from __future__ import annotations

import copy
import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml


@dataclass
class Config:
    """Resolved training configuration. Serializable to YAML/JSON."""

    # ── Identification ──────────────────────────────────────────────────
    run_id: str = ""  # Set at runtime

    # ── Environment ─────────────────────────────────────────────────────
    environment_kind: str = "synthetic"
    environment_version: str = "v1"

    # ── State / action schema ───────────────────────────────────────────
    state_dim: int = 16
    action_count: int = 5
    state_schema_version: int = 1
    action_schema_version: int = 1

    # ── Network ─────────────────────────────────────────────────────────
    hidden_dim: int = 64
    algorithm: str = "double_dqn"

    # ── Optimizer ───────────────────────────────────────────────────────
    learning_rate: float = 0.001
    optimizer: str = "adam"

    # ── DQN ─────────────────────────────────────────────────────────────
    gamma: float = 0.9
    batch_size: int = 64
    replay_buffer_capacity: int = 50_000
    target_network_refresh: int = 500  # training steps
    loss_type: str = "huber"
    huber_delta: float = 1.0

    # ── Exploration ─────────────────────────────────────────────────────
    epsilon_start: float = 1.0
    epsilon_min: float = 0.05
    epsilon_decay: float = 0.9995  # per episode

    # ── Training ────────────────────────────────────────────────────────
    num_episodes: int = 40_000
    max_steps_per_episode: int = 10
    seeds: List[int] = field(default_factory=lambda: [1, 2, 3, 4, 5])

    # ── Evaluation ──────────────────────────────────────────────────────
    eval_frequency: int = 2_000  # episodes
    eval_episodes: int = 500
    eval_seed: int = 99

    # ── Weight initialization ───────────────────────────────────────────
    weight_init: str = "kaiming"

    # ── Metrics streaming ───────────────────────────────────────────────
    metric_step_interval: int = 100  # emit step-level every N steps

    # ── Paths ───────────────────────────────────────────────────────────
    output_dir: str = "artifacts"
    checkpoint_dir: str = "artifacts/checkpoints"
    metrics_dir: str = "artifacts/metrics"
    export_dir: str = "artifacts/export"
    report_dir: str = "reports"

    # ── Reward design ───────────────────────────────────────────────────
    reward_correct_confirm: float = 10.0
    reward_per_turn: float = -1.0
    reward_unresolved_attempt: float = -3.0
    reward_abandon: float = -5.0
    reward_incorrect_destination: float = -10.0

    # ── Simulator probabilities (synthetic env) ─────────────────────────
    sim_base_success_prob: float = 0.35
    sim_confidence_boost: float = 0.15
    sim_saved_place_boost: float = 0.20
    sim_confirm_success_prob: float = 0.60
    sim_escape_end_prob: float = 0.40
    sim_abandon_prob: float = 0.10

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    def to_yaml(self) -> str:
        return yaml.dump(self.to_dict(), default_flow_style=False, sort_keys=False)

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), indent=2)

    def save(self, path: str | Path) -> None:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w") as f:
            f.write(self.to_yaml())

    @classmethod
    def from_yaml(cls, path: str | Path) -> "Config":
        with open(path) as f:
            data = yaml.safe_load(f)
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Config":
        return cls(**{k: v for k, v in d.items() if k in cls.__dataclass_fields__})

    def copy(self) -> "Config":
        return copy.deepcopy(self)


def default_config() -> Config:
    """Return a fresh default config."""
    return Config()


def load_config(path: Optional[str | Path] = None) -> Config:
    """Load config from YAML if path exists, otherwise return default."""
    if path and Path(path).exists():
        return Config.from_yaml(path)
    return default_config()
