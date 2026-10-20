"""
train.py — Double DQN training loop with live metrics streaming.

Trains the agent in the synthetic dialogue recovery environment and emits
metrics events after every episode and at evaluation checkpoints.

Usage:
    python -m dialogue_recovery.train                     # default config
    python -m dialogue_recovery.train --config config.yaml
    python -m dialogue_recovery.train --seed 2 --episodes 10000
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from collections import Counter, deque
from pathlib import Path
from typing import Callable, Dict, List, Optional

if __package__ in (None, ""):
    import sys
    from pathlib import Path
    _pkg_dir = Path(__file__).resolve().parent
    if str(_pkg_dir.parent) not in sys.path:
        sys.path.insert(0, str(_pkg_dir.parent))
    __package__ = _pkg_dir.name

import numpy as np
import torch

from .actions import ACTION_COUNT, ACTION_NAMES, RecoveryAction, action_mask
from .config import Config, load_config
from .dqn_model import DQNAgent
from .environment import DialogueRecoveryEnv, ScenarioType
from .evaluate import compare_policies, evaluate_dqn
from .metrics import EpisodeMetrics, MetricsLogger
from .replay_buffer import ReplayBuffer, Transition
from .reward import Outcome
from .state_schema import STATE_DIM


def train(
    config: Config,
    seed: int = 1,
    broadcast_fn: Optional[Callable[[str], None]] = None,
    stop_flag: Optional[Callable[[], bool]] = None,
) -> Dict:
    """
    Train a Double DQN agent and return the final comparison results.

    Args:
        config: Training configuration.
        seed: Random seed for this training run.
        broadcast_fn: Optional callback to broadcast metric events (e.g., to WebSocket).
        stop_flag: Optional callable returning True to request early stop.

    Returns:
        Dictionary with run_id, final eval results, and output paths.
    """
    run_id = f"run-{seed}-{uuid.uuid4().hex[:8]}"
    config = config.copy()
    config.run_id = run_id

    # ── Seed everything ─────────────────────────────────────────────────
    torch.manual_seed(seed)
    np.random.seed(seed)

    device = torch.device("cpu")

    # ── Create output directories ───────────────────────────────────────
    checkpoint_dir = Path(config.checkpoint_dir) / run_id
    checkpoint_dir.mkdir(parents=True, exist_ok=True)

    export_dir = Path(config.export_dir)
    export_dir.mkdir(parents=True, exist_ok=True)

    # Save resolved config
    config.save(checkpoint_dir / "config.yaml")

    # ── Initialize components ───────────────────────────────────────────
    env = DialogueRecoveryEnv(config, seed=seed)
    agent = DQNAgent(config, device=device)
    replay = ReplayBuffer(capacity=config.replay_buffer_capacity, seed=seed)
    logger = MetricsLogger(run_id=run_id, output_dir=config.metrics_dir, broadcast_fn=broadcast_fn)

    # Log start
    logger.log(EpisodeMetrics(
        event_type="status",
        status="training",
        seed=seed,
        environment_kind=config.environment_kind,
    ))

    # ── Training metrics tracking ───────────────────────────────────────
    reward_window: deque = deque(maxlen=100)
    loss_window: deque = deque(maxlen=100)
    total_steps = 0
    best_eval_return = -float("inf")

    start_time = time.time()

    # ── Training loop ───────────────────────────────────────────────────
    for episode in range(1, config.num_episodes + 1):
        # Check for stop request
        if stop_flag and stop_flag():
            logger.log(EpisodeMetrics(event_type="status", status="stopped", episode=episode))
            break

        state, mask = env.reset()
        ep_reward = 0.0
        ep_steps = 0
        done = False
        ep_actions: Counter = Counter()

        while not done:
            action = agent.select_action(state, mask, explore=True)
            ep_actions[ACTION_NAMES[action]] += 1
            next_state, reward, done, next_mask, info = env.step(action)

            replay.push(Transition(
                state=state,
                action=action,
                reward=reward,
                next_state=next_state,
                done=done,
                mask=mask,
                next_mask=next_mask,
            ))

            state, mask = next_state, next_mask
            ep_reward += reward
            ep_steps += 1
            total_steps += 1

            # ── Train step ──────────────────────────────────────────
            if len(replay) >= config.batch_size:
                batch = replay.sample(config.batch_size, device=device)
                loss = agent.train_step(
                    batch.states, batch.actions, batch.rewards,
                    batch.next_states, batch.dones, batch.masks, batch.next_masks,
                )
                loss_window.append(loss)

        # ── Post-episode ────────────────────────────────────────────────
        agent.decay_epsilon()
        reward_window.append(ep_reward)

        # Log episode metrics
        rolling_reward = float(np.mean(reward_window)) if reward_window else None
        avg_loss = float(np.mean(loss_window)) if loss_window else None

        logger.log(EpisodeMetrics(
            event_type="episode_metrics",
            phase="training",
            seed=seed,
            episode=episode,
            environment_steps=total_steps,
            episode_reward=round(ep_reward, 4),
            rolling_mean_reward=round(rolling_reward, 4) if rolling_reward is not None else None,
            td_loss=round(avg_loss, 6) if avg_loss is not None else None,
            epsilon=round(agent.epsilon, 6),
            action_distribution=dict(ep_actions),
            environment_kind=config.environment_kind,
            status="training",
        ))

        # ── Evaluation checkpoint ───────────────────────────────────────
        if episode % config.eval_frequency == 0:
            eval_result = evaluate_dqn(agent, config)

            logger.log(EpisodeMetrics(
                event_type="eval_metrics",
                phase="evaluation",
                seed=seed,
                episode=episode,
                environment_steps=total_steps,
                evaluation_mean_reward=eval_result.mean_return,
                evaluation_success_rate=eval_result.success_rate,
                td_loss=round(avg_loss, 6) if avg_loss is not None else None,
                success_rate=eval_result.success_rate,
                incorrect_destination_rate=eval_result.incorrect_destination_rate,
                mean_turns=eval_result.mean_turns,
                abandon_rate=eval_result.abandon_rate,
                escape_rate=eval_result.escape_rate,
                unresolved_rate=eval_result.unresolved_rate,
                action_distribution=eval_result.action_distribution,
                environment_kind=config.environment_kind,
                status="evaluating",
            ))

            # Save checkpoint if best
            if eval_result.mean_return > best_eval_return:
                best_eval_return = eval_result.mean_return
                agent.save_checkpoint(checkpoint_dir / "best.pt")

            # Also save periodic checkpoint
            agent.save_checkpoint(checkpoint_dir / f"ep{episode}.pt")

            print(
                f"  [Eval @ ep {episode}] "
                f"success={eval_result.success_rate:.3f} "
                f"turns={eval_result.mean_turns:.2f} "
                f"return={eval_result.mean_return:.4f} "
                f"ε={agent.epsilon:.4f}"
            )

            # Auto-export test metrics & EDA plots during training
            try:
                from .eda import generate_eda_report
                eda_dir = Path(config.report_dir) / run_id / "eda"
                generate_eda_report(logger.filepath, output_dir=eda_dir)
            except Exception as ex:
                pass  # Non-fatal

    # ── Final evaluation ────────────────────────────────────────────────
    elapsed = round(time.time() - start_time, 2)
    print(f"\n  Training completed in {elapsed}s. Running final comparison...")

    agent.save_checkpoint(checkpoint_dir / "final.pt")

    # Compare against baselines
    comparison = compare_policies(agent, config)

    # Export model weights for Kotlin inference
    export_path = export_dir / f"recovery_policy_seed{seed}.json"
    agent.export_weights(export_path)

    # Save comparison results
    comparison_path = Path(config.report_dir) / run_id / "comparison.json"
    comparison_path.parent.mkdir(parents=True, exist_ok=True)
    comparison_data = {name: r.to_dict() for name, r in comparison.items()}
    with open(comparison_path, "w") as f:
        json.dump(comparison_data, f, indent=2)

    # Final comprehensive EDA & Test Metrics export
    try:
        from .eda import generate_eda_report
        eda_dir = Path(config.report_dir) / run_id / "eda"
        global_eda_dir = Path(config.output_dir) / "eda"
        generate_eda_report(logger.filepath, output_dir=eda_dir)
        generate_eda_report(logger.filepath, output_dir=global_eda_dir)
    except Exception as ex:
        pass

    # Log completion
    logger.log(EpisodeMetrics(
        event_type="status",
        status="completed",
        seed=seed,
        episode=config.num_episodes,
        environment_steps=total_steps,
        elapsed_seconds=elapsed,
        environment_kind=config.environment_kind,
    ))

    print(f"\n  ═══ Seed {seed} Final Comparison ═══")
    print(f"  {'Policy':<20} {'Success':>8} {'Abandon':>8} {'Escape':>8} {'Turns':>8} {'Return':>10}")
    print(f"  {'─' * 62}")
    for name, result in comparison.items():
        print(
            f"  {name:<20} {result.success_rate:>8.3f} {result.abandon_rate:>8.3f} "
            f"{result.escape_rate:>8.3f} {result.mean_turns:>8.2f} {result.mean_return:>10.4f}"
        )

    return {
        "run_id": run_id,
        "seed": seed,
        "elapsed_seconds": elapsed,
        "comparison": comparison_data,
        "checkpoint_dir": str(checkpoint_dir),
        "export_path": str(export_path),
        "metrics_path": str(logger.filepath),
    }


def multi_seed_train(
    config: Config,
    broadcast_fn: Optional[Callable[[str], None]] = None,
    stop_flag: Optional[Callable[[], bool]] = None,
) -> List[Dict]:
    """Train across all configured seeds and return all results."""
    results = []
    for seed in config.seeds:
        print(f"\n{'═' * 60}")
        print(f"  Training seed {seed} / {len(config.seeds)} — {config.num_episodes} episodes")
        print(f"{'═' * 60}")
        result = train(config, seed=seed, broadcast_fn=broadcast_fn, stop_flag=stop_flag)
        results.append(result)

        if stop_flag and stop_flag():
            break

    return results


# ── CLI entry point ──────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Train DQN dialogue recovery agent")
    parser.add_argument("--config", type=str, default=None, help="Path to config YAML")
    parser.add_argument("--seed", type=int, default=None, help="Single seed (overrides config)")
    parser.add_argument("--episodes", type=int, default=None, help="Override num_episodes")
    parser.add_argument("--eval-episodes", type=int, default=None, help="Override eval_episodes")
    parser.add_argument("--multi", action="store_true", help="Train all seeds")
    args = parser.parse_args()

    config = load_config(args.config)
    if args.episodes:
        config.num_episodes = args.episodes
    if args.eval_episodes:
        config.eval_episodes = args.eval_episodes

    print("Cab Eye — DQN Dialogue Recovery Training")
    print("SIMULATED ENVIRONMENT — NOT VALIDATED ON REAL RIDERS")
    print(f"Config: state_dim={config.state_dim}, hidden_dim={config.hidden_dim}, "
          f"actions={config.action_count}, algorithm={config.algorithm}")
    print(f"Hyperparameters: lr={config.learning_rate}, γ={config.gamma}, "
          f"batch={config.batch_size}, target_refresh={config.target_network_refresh}")

    if args.multi:
        results = multi_seed_train(config)
        print(f"\n\nCompleted {len(results)} seed runs.")
    else:
        seed = args.seed or config.seeds[0]
        result = train(config, seed=seed)
        print(f"\nRun ID: {result['run_id']}")
        print(f"Metrics: {result['metrics_path']}")
        print(f"Checkpoint: {result['checkpoint_dir']}")
        print(f"Exported: {result['export_path']}")


if __name__ == "__main__":
    main()
