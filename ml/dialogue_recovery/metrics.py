"""
metrics.py — Training metrics event schema and persistence.

Metrics are emitted as JSONL events from the training process and consumed
by the live dashboard via WebSocket. Each event carries its own run_id and
timestamp so separate runs are never mixed.
"""

from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional


@dataclass
class EpisodeMetrics:
    """Metric event emitted after each episode or evaluation checkpoint."""

    run_id: str = ""
    timestamp: str = ""
    event_type: str = "episode_metrics"        # episode_metrics | eval_metrics | step_metrics
    phase: str = "training"                     # training | evaluation
    seed: int = 0
    episode: int = 0
    environment_steps: int = 0
    episode_reward: Optional[float] = None
    rolling_mean_reward: Optional[float] = None
    evaluation_mean_reward: Optional[float] = None
    evaluation_success_rate: Optional[float] = None
    td_loss: Optional[float] = None
    epsilon: Optional[float] = None
    success_rate: Optional[float] = None
    incorrect_destination_rate: Optional[float] = None
    mean_turns: Optional[float] = None
    abandon_rate: Optional[float] = None
    escape_rate: Optional[float] = None
    unresolved_rate: Optional[float] = None
    fallback_rate: Optional[float] = None
    action_distribution: Optional[Dict[str, int]] = None
    elapsed_seconds: Optional[float] = None
    environment_kind: str = "synthetic"
    status: str = "training"                    # training | evaluating | completed | failed | stopped

    def to_dict(self) -> Dict[str, Any]:
        d = asdict(self)
        if not d["timestamp"]:
            d["timestamp"] = datetime.now(timezone.utc).isoformat()
        return d

    def to_json(self) -> str:
        return json.dumps(self.to_dict())


class MetricsLogger:
    """
    Append-only JSONL metrics logger with optional WebSocket broadcast.

    Usage:
        logger = MetricsLogger(run_id="run-001", output_dir="artifacts/metrics")
        logger.log(EpisodeMetrics(episode=1, episode_reward=5.0))
    """

    def __init__(
        self,
        run_id: str,
        output_dir: str | Path = "artifacts/metrics",
        broadcast_fn: Optional[Callable[[str], None]] = None,
    ):
        self.run_id = run_id
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.filepath = self.output_dir / f"{run_id}.jsonl"
        self.test_csv_path = self.output_dir / f"{run_id}_test_metrics.csv"
        self.train_csv_path = self.output_dir / f"{run_id}_training_metrics.csv"
        self.global_test_csv = self.output_dir.parent / "test_metrics.csv"
        self.global_train_csv = self.output_dir.parent / "training_metrics.csv"
        self.broadcast_fn = broadcast_fn
        self._start_time = time.time()
        self._latest_loss: Optional[float] = None

        # Initialize CSV headers if files do not exist
        self._init_csv_headers()

    def _init_csv_headers(self) -> None:
        """Write CSV headers if files are newly created."""
        test_header = (
            "timestamp,episode,environment_steps,accuracy,td_loss,error_incorrect_destination,"
            "error_abandonment,error_unresolved,total_error_rate,escape_rate,mean_return,mean_turns\n"
        )
        if not self.test_csv_path.exists():
            with open(self.test_csv_path, "w", encoding="utf-8") as f:
                f.write(test_header)

        with open(self.global_test_csv, "w", encoding="utf-8") as f:
            f.write(test_header)

        train_header = "timestamp,episode,environment_steps,reward,rolling_reward,td_loss,epsilon\n"
        if not self.train_csv_path.exists():
            with open(self.train_csv_path, "w", encoding="utf-8") as f:
                f.write(train_header)

    def log(self, metrics: EpisodeMetrics) -> None:
        """Log a metrics event to JSONL, CSV, and optionally broadcast."""
        metrics.run_id = self.run_id
        metrics.elapsed_seconds = round(time.time() - self._start_time, 2)
        if not metrics.timestamp:
            metrics.timestamp = datetime.now(timezone.utc).isoformat()

        if metrics.td_loss is not None:
            self._latest_loss = metrics.td_loss

        line = metrics.to_json()

        # Append to JSONL
        with open(self.filepath, "a", encoding="utf-8") as f:
            f.write(line + "\n")

        # Export test metrics to CSV during evaluation checkpoints
        if metrics.event_type == "eval_metrics" or metrics.phase == "evaluation":
            accuracy = metrics.evaluation_success_rate or metrics.success_rate or 0.0
            loss = metrics.td_loss if metrics.td_loss is not None else (self._latest_loss or 0.0)
            err_dest = metrics.incorrect_destination_rate or 0.0
            err_abandon = metrics.abandon_rate or 0.0
            err_unresolved = metrics.unresolved_rate or 0.0
            total_err = round(err_dest + err_abandon + err_unresolved, 4)
            escape = metrics.escape_rate or 0.0
            m_return = metrics.evaluation_mean_reward if metrics.evaluation_mean_reward is not None else (metrics.rolling_mean_reward or 0.0)
            m_turns = metrics.mean_turns or 0.0

            row = (
                f"{metrics.timestamp},{metrics.episode},{metrics.environment_steps},"
                f"{accuracy:.4f},{loss:.6f},{err_dest:.4f},{err_abandon:.4f},"
                f"{err_unresolved:.4f},{total_err:.4f},{escape:.4f},{m_return:.4f},{m_turns:.2f}\n"
            )
            with open(self.test_csv_path, "a", encoding="utf-8") as f:
                f.write(row)
            with open(self.global_test_csv, "a", encoding="utf-8") as f:
                f.write(row)

        elif metrics.event_type == "episode_metrics":
            rew = metrics.episode_reward or 0.0
            roll_rew = metrics.rolling_mean_reward if metrics.rolling_mean_reward is not None else rew
            loss = metrics.td_loss if metrics.td_loss is not None else 0.0
            eps = metrics.epsilon if metrics.epsilon is not None else 1.0

            row = (
                f"{metrics.timestamp},{metrics.episode},{metrics.environment_steps},"
                f"{rew:.4f},{roll_rew:.4f},{loss:.6f},{eps:.4f}\n"
            )
            with open(self.train_csv_path, "a", encoding="utf-8") as f:
                f.write(row)

        # Broadcast if a callback is registered (e.g., WebSocket sender)
        if self.broadcast_fn:
            try:
                self.broadcast_fn(line)
            except Exception:
                pass  # Don't let broadcast failures stop training

    def load_history(self) -> List[Dict[str, Any]]:
        """Load all events from the JSONL file."""
        events = []
        if self.filepath.exists():
            with open(self.filepath, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line:
                        try:
                            events.append(json.loads(line))
                        except json.JSONDecodeError:
                            continue
        return events

    @staticmethod
    def list_runs(output_dir: str | Path = "artifacts/metrics") -> List[str]:
        """List all run IDs with saved metrics."""
        d = Path(output_dir)
        if not d.exists():
            return []
        return sorted([p.stem for p in d.glob("*.jsonl")])
