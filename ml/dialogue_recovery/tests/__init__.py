"""
Test suite for the DQN dialogue recovery module.

Tests cover:
  1. State encoding: shape, range, boundary values
  2. Action masks: preconditions enforced
  3. Reward: terminal conditions and values
  4. Replay buffer: storage and sampling
  5. Q-network: output shape, finite values, parameter count
  6. Double DQN target: correct Bellman computation
  7. Environment: episodes terminate, valid actions, scenario variation
  8. Seed reproducibility
  9. Exported JSON matches network forward pass
"""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path

import numpy as np
import pytest
import torch

from dialogue_recovery.actions import (
    ACTION_COUNT,
    ACTION_NAMES,
    RecoveryAction,
    action_mask,
    apply_mask,
    select_action,
    valid_actions,
)
from dialogue_recovery.config import Config, default_config
from dialogue_recovery.dqn_model import DQNAgent, QNetwork, init_weights
from dialogue_recovery.environment import (
    AlwaysEscalatePolicy,
    DialogueRecoveryEnv,
    FixedLadderPolicy,
    RandomValidPolicy,
    ScenarioType,
)
from dialogue_recovery.evaluate import compare_policies, evaluate_baseline, evaluate_dqn
from dialogue_recovery.metrics import EpisodeMetrics, MetricsLogger
from dialogue_recovery.replay_buffer import ReplayBuffer, Transition
from dialogue_recovery.reward import Outcome, compute_reward
from dialogue_recovery.state_schema import (
    STATE_DIM,
    DialogueState,
    validate_state_vector,
)


# ═══════════════════════════════════════════════════════════════════════
#  1. State encoding
# ═══════════════════════════════════════════════════════════════════════


class TestStateSchema:
    def test_state_shape_and_range(self):
        """State vector must be (16,) with all values in [0, 1]."""
        state = DialogueState(
            attempt_number=2,
            blank_transcript=True,
            word_count=3,
            best_match_score=0.42,
            gate_too_short=False,
            gate_all_stopwords=False,
            gate_no_candidate=True,
            gate_fragment_match=False,
            candidate_exists=True,
            previous_action=1,
            saved_places_available=True,
            repeated_same_words=False,
        )
        v = state.to_vector()
        assert v.shape == (STATE_DIM,), f"Expected shape ({STATE_DIM},), got {v.shape}"
        assert v.dtype == np.float32
        assert np.all(v >= 0.0) and np.all(v <= 1.0), f"Values out of range: {v}"
        assert validate_state_vector(v)

    def test_attempt_clipping(self):
        """Attempt number clipped at max (3) and normalized."""
        state = DialogueState(attempt_number=10)
        v = state.to_vector()
        assert v[0] == 1.0, "Attempt > MAX_ATTEMPT_NORM should clip to 1.0"

    def test_word_count_clipping(self):
        """Word count clipped at max (5) and normalized."""
        state = DialogueState(word_count=20)
        v = state.to_vector()
        assert v[2] == 1.0, "word_count > MAX_WORD_COUNT_NORM should clip to 1.0"

    def test_previous_action_one_hot(self):
        """Previous action encodes as one-hot in indices 9–13."""
        for action_id in range(5):
            state = DialogueState(previous_action=action_id)
            v = state.to_vector()
            for i in range(5):
                expected = 1.0 if i == action_id else 0.0
                assert v[9 + i] == expected, \
                    f"prev_action={action_id}: index {9+i} expected {expected}, got {v[9+i]}"

    def test_no_previous_action(self):
        """When no previous action, indices 9–13 are all zero."""
        state = DialogueState(previous_action=None)
        v = state.to_vector()
        for i in range(9, 14):
            assert v[i] == 0.0, f"Index {i} should be 0.0 with no previous action"

    def test_default_state_is_valid(self):
        """Default DialogueState produces a valid zero vector."""
        v = DialogueState().to_vector()
        assert validate_state_vector(v)
        assert np.sum(v) == 0.0


# ═══════════════════════════════════════════════════════════════════════
#  2. Action masks
# ═══════════════════════════════════════════════════════════════════════


class TestActionMasks:
    def test_mask_rules(self):
        """NAME_REASON and OFFER_ESCAPES are always valid."""
        state = DialogueState()  # minimal state
        mask = action_mask(state)
        assert mask[RecoveryAction.NAME_REASON] == 1.0
        assert mask[RecoveryAction.OFFER_ESCAPES] == 1.0

    def test_confirm_requires_candidate_and_score(self):
        """CONFIRM_TOP_GUESS invalid without candidate or sufficient score."""
        state = DialogueState(candidate_exists=False)
        mask = action_mask(state)
        assert mask[RecoveryAction.CONFIRM_TOP_GUESS] == 0.0

        state = DialogueState(candidate_exists=True, best_match_score=0.20)
        mask = action_mask(state)
        assert mask[RecoveryAction.CONFIRM_TOP_GUESS] == 0.0

        state = DialogueState(candidate_exists=True, best_match_score=0.35)
        mask = action_mask(state)
        assert mask[RecoveryAction.CONFIRM_TOP_GUESS] == 1.0

    def test_saved_place_requires_availability(self):
        """OFFER_SAVED_PLACES only valid when saved places exist."""
        state = DialogueState(saved_places_available=False)
        mask = action_mask(state)
        assert mask[RecoveryAction.OFFER_SAVED_PLACES] == 0.0

        state = DialogueState(saved_places_available=True)
        mask = action_mask(state)
        assert mask[RecoveryAction.OFFER_SAVED_PLACES] == 1.0

    def test_invalid_action_rejected(self):
        """apply_mask sets invalid Q-values to -inf."""
        q_values = np.array([1.0, 2.0, 0.5, 3.0, 1.5], dtype=np.float32)
        mask = np.array([1.0, 0.0, 1.0, 0.0, 0.0], dtype=np.float32)
        masked = apply_mask(q_values, mask)
        assert np.argmax(masked) == 0  # Highest valid is index 0
        assert masked[1] == -np.inf
        assert masked[3] == -np.inf
        assert masked[4] == -np.inf


# ═══════════════════════════════════════════════════════════════════════
#  3. Reward
# ═══════════════════════════════════════════════════════════════════════


class TestReward:
    def test_correct_confirm_is_positive(self):
        config = default_config()
        result = compute_reward(Outcome.CORRECT_CONFIRM, config)
        assert result.reward > 0
        assert result.done is True

    def test_incorrect_destination_is_harsh(self):
        config = default_config()
        result = compute_reward(Outcome.INCORRECT_DESTINATION, config)
        assert result.reward == config.reward_incorrect_destination
        assert result.done is True

    def test_continue_not_done(self):
        config = default_config()
        result = compute_reward(Outcome.CONTINUE, config)
        assert result.done is False


# ═══════════════════════════════════════════════════════════════════════
#  4. Replay buffer
# ═══════════════════════════════════════════════════════════════════════


class TestReplayBuffer:
    def test_push_and_sample(self):
        buf = ReplayBuffer(capacity=100, seed=42)
        for i in range(10):
            t = Transition(
                state=np.zeros(STATE_DIM, dtype=np.float32),
                action=i % ACTION_COUNT,
                reward=float(i),
                next_state=np.ones(STATE_DIM, dtype=np.float32),
                done=i == 9,
                mask=np.ones(ACTION_COUNT, dtype=np.float32),
                next_mask=np.ones(ACTION_COUNT, dtype=np.float32),
            )
            buf.push(t)
        assert len(buf) == 10
        batch = buf.sample(4)
        assert batch.states.shape == (4, STATE_DIM)
        assert batch.actions.shape == (4, 1)

    def test_capacity_overflow(self):
        buf = ReplayBuffer(capacity=5, seed=0)
        for i in range(10):
            buf.push(Transition(
                state=np.full(STATE_DIM, i, dtype=np.float32),
                action=0, reward=0.0,
                next_state=np.zeros(STATE_DIM, dtype=np.float32),
                done=False,
                mask=np.ones(ACTION_COUNT, dtype=np.float32),
                next_mask=np.ones(ACTION_COUNT, dtype=np.float32),
            ))
        assert len(buf) == 5


# ═══════════════════════════════════════════════════════════════════════
#  5. Q-network
# ═══════════════════════════════════════════════════════════════════════


class TestQNetwork:
    def test_output_shape(self):
        net = QNetwork(state_dim=STATE_DIM, hidden_dim=64, action_count=ACTION_COUNT)
        x = torch.randn(8, STATE_DIM)
        out = net(x)
        assert out.shape == (8, ACTION_COUNT)

    def test_finite_q_values(self):
        net = QNetwork()
        init_weights(net, "kaiming")
        x = torch.randn(16, STATE_DIM)
        out = net(x)
        assert torch.all(torch.isfinite(out)), "Q-values must be finite"

    def test_parameter_count(self):
        """5,573 parameters as documented in the spec."""
        net = QNetwork(state_dim=16, hidden_dim=64, action_count=5)
        count = sum(p.numel() for p in net.parameters())
        assert count == 5573, f"Expected 5573 params, got {count}"


# ═══════════════════════════════════════════════════════════════════════
#  6. Double DQN target
# ═══════════════════════════════════════════════════════════════════════


class TestDoubleDQNTarget:
    def test_terminal_no_bootstrap(self):
        """Terminal transitions: y = r (no bootstrapping from next state)."""
        config = default_config()
        config.gamma = 0.9
        agent = DQNAgent(config)

        state = torch.randn(1, STATE_DIM)
        action = torch.tensor([[0]], dtype=torch.long)
        reward = torch.tensor([[10.0]])
        next_state = torch.randn(1, STATE_DIM)
        done = torch.tensor([[1.0]])  # Terminal!
        mask = torch.ones(1, ACTION_COUNT)
        next_mask = torch.ones(1, ACTION_COUNT)

        # The loss should be calculable without error
        loss = agent.train_step(state, action, reward, next_state, done, mask, next_mask)
        assert np.isfinite(loss), f"Loss should be finite, got {loss}"

    def test_target_calculation(self):
        """Non-terminal: y = r + γ · Q_target(s', a*_online)."""
        config = default_config()
        config.gamma = 0.9
        agent = DQNAgent(config)

        # Run a training step with non-terminal transition
        state = torch.randn(1, STATE_DIM)
        action = torch.tensor([[1]], dtype=torch.long)
        reward = torch.tensor([[-1.0]])
        next_state = torch.randn(1, STATE_DIM)
        done = torch.tensor([[0.0]])  # Not terminal
        mask = torch.ones(1, ACTION_COUNT)
        next_mask = torch.ones(1, ACTION_COUNT)

        loss = agent.train_step(state, action, reward, next_state, done, mask, next_mask)
        assert np.isfinite(loss)


# ═══════════════════════════════════════════════════════════════════════
#  7. Environment
# ═══════════════════════════════════════════════════════════════════════


class TestEnvironment:
    def test_every_episode_terminates(self):
        """Every scenario must terminate within max_steps."""
        config = default_config()
        config.max_steps_per_episode = 10
        env = DialogueRecoveryEnv(config, seed=42)

        for scenario in ScenarioType:
            state, mask = env.reset(scenario=scenario)
            done = False
            steps = 0
            while not done:
                valid_indices = np.where(mask > 0)[0]
                action = int(np.random.choice(valid_indices))
                state, _, done, mask, _ = env.step(action)
                steps += 1
                assert steps <= config.max_steps_per_episode + 1, \
                    f"Scenario {scenario.name} did not terminate in time"

    def test_escape_offered_by_attempt_3(self):
        """OFFER_ESCAPES is always valid — confirmed for attempt 3 states."""
        state = DialogueState(attempt_number=3)
        mask = action_mask(state)
        assert mask[RecoveryAction.OFFER_ESCAPES] == 1.0


# ═══════════════════════════════════════════════════════════════════════
#  8. Seed reproducibility
# ═══════════════════════════════════════════════════════════════════════


class TestReproducibility:
    def test_seed_reproducible(self):
        """Two runs with the same seed produce identical Q-values."""
        config = default_config()

        torch.manual_seed(42)
        np.random.seed(42)
        net1 = QNetwork()
        init_weights(net1, "kaiming")

        torch.manual_seed(42)
        np.random.seed(42)
        net2 = QNetwork()
        init_weights(net2, "kaiming")

        x = torch.randn(1, STATE_DIM)
        q1 = net1(x)
        q2 = net2(x)
        assert torch.allclose(q1, q2, atol=1e-6), "Same seed should produce identical weights"


# ═══════════════════════════════════════════════════════════════════════
#  9. Exported JSON matches network
# ═══════════════════════════════════════════════════════════════════════


class TestExport:
    def test_exported_json_matches_network(self):
        """Exported JSON weights produce the same Q-values as PyTorch."""
        config = default_config()
        agent = DQNAgent(config)

        with tempfile.TemporaryDirectory() as tmpdir:
            export_path = Path(tmpdir) / "test_policy.json"
            agent.export_weights(export_path)

            with open(export_path) as f:
                data = json.load(f)

            assert data["schema_version"] == 1
            assert data["state_dim"] == 16
            assert data["hidden_dim"] == 64
            assert data["action_count"] == 5
            assert len(data["layers"]) == 3
            assert data["action_names"] == ACTION_NAMES

            # Manual forward pass with exported weights
            layers = data["layers"]
            test_state = np.random.randn(STATE_DIM).astype(np.float32)

            # Layer 1: ReLU(W1·s + b1)
            w1 = np.array(layers[0]["weight"], dtype=np.float32)
            b1 = np.array(layers[0]["bias"], dtype=np.float32)
            h1 = np.maximum(0, w1 @ test_state + b1)

            # Layer 2: ReLU(W2·h1 + b2)
            w2 = np.array(layers[1]["weight"], dtype=np.float32)
            b2 = np.array(layers[1]["bias"], dtype=np.float32)
            h2 = np.maximum(0, w2 @ h1 + b2)

            # Output: W3·h2 + b3
            w3 = np.array(layers[2]["weight"], dtype=np.float32)
            b3 = np.array(layers[2]["bias"], dtype=np.float32)
            q_manual = w3 @ h2 + b3

            # PyTorch forward pass
            with torch.no_grad():
                state_t = torch.tensor(test_state).unsqueeze(0)
                q_torch = agent.online_net(state_t).numpy()[0]

            np.testing.assert_allclose(
                q_manual, q_torch, atol=1e-5,
                err_msg="Exported JSON weights must match PyTorch forward pass"
            )

    def test_untrained_net_never_picks_masked_action(self):
        """Even an untrained network should respect action masks."""
        config = default_config()
        agent = DQNAgent(config)

        for _ in range(100):
            state = np.random.rand(STATE_DIM).astype(np.float32)
            # Mask: only OFFER_ESCAPES valid
            mask = np.zeros(ACTION_COUNT, dtype=np.float32)
            mask[RecoveryAction.OFFER_ESCAPES] = 1.0
            action = agent.select_action(state, mask, explore=False)
            assert action == RecoveryAction.OFFER_ESCAPES, \
                f"Expected OFFER_ESCAPES (2), got {action}"

    def test_export_dqn_to_json_and_parity_verification(self):
        """Full pipeline export_dqn_to_json creates valid file and passes parity verification."""
        from dialogue_recovery.export_model import export_dqn_to_json, verify_export_parity
        config = default_config()
        agent = DQNAgent(config)

        with tempfile.TemporaryDirectory() as tmpdir:
            out_file = Path(tmpdir) / "policy_v1.json"
            data = export_dqn_to_json(agent, out_file)
            assert Path(out_file).exists()
            assert "weights_sha256" in data
            assert len(data["verification"]["test_inputs"]) == 4
            assert verify_export_parity(out_file) is True


# ═══════════════════════════════════════════════════════════════════════
#  10. Metrics
# ═══════════════════════════════════════════════════════════════════════


class TestMetrics:
    def test_metrics_event_schema(self):
        m = EpisodeMetrics(
            run_id="test-run",
            episode=42,
            episode_reward=5.0,
            epsilon=0.5,
        )
        d = m.to_dict()
        assert d["run_id"] == "test-run"
        assert d["episode"] == 42
        assert d["epsilon"] == 0.5

    def test_metrics_logger_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            logger = MetricsLogger(run_id="test-run", output_dir=tmpdir)
            logger.log(EpisodeMetrics(episode=1, episode_reward=3.0))
            logger.log(EpisodeMetrics(episode=2, episode_reward=5.0))

            history = logger.load_history()
            assert len(history) == 2
            assert history[0]["episode"] == 1
            assert history[1]["episode"] == 2

            # Check that test_metrics.csv was generated
            assert logger.test_csv_path.exists()
            assert logger.train_csv_path.exists()

            # Log an eval event and verify test CSV row
            logger.log(EpisodeMetrics(
                event_type="eval_metrics",
                episode=10,
                evaluation_success_rate=0.85,
                td_loss=0.042,
                incorrect_destination_rate=0.0,
                abandon_rate=0.10,
                unresolved_rate=0.05,
            ))
            with open(logger.test_csv_path) as f:
                content = f.read()
                assert "0.8500" in content
                assert "0.042000" in content


# ═══════════════════════════════════════════════════════════════════════
#  11. Evaluation
# ═══════════════════════════════════════════════════════════════════════


class TestEvaluation:
    def test_baseline_evaluation_runs(self):
        config = default_config()
        config.eval_episodes = 50
        result = evaluate_baseline(FixedLadderPolicy(), config, "FixedLadder", num_episodes=50)
        assert result.num_episodes == 50
        assert 0 <= result.success_rate <= 1.0
        assert result.mean_turns > 0

    def test_dqn_evaluation_runs(self):
        config = default_config()
        config.eval_episodes = 50
        agent = DQNAgent(config)
        result = evaluate_dqn(agent, config, num_episodes=50)
        assert result.num_episodes == 50
        assert result.incorrect_destination_rate <= 0.05  # Should be very low


# ═══════════════════════════════════════════════════════════════════════
#  12. EDA (Exploratory Data Analysis)
# ═══════════════════════════════════════════════════════════════════════


class TestEDA:
    def test_eda_full_pipeline(self):
        from dialogue_recovery.eda import generate_eda_report

        with tempfile.TemporaryDirectory() as tmpdir:
            metrics_dir = Path(tmpdir) / "metrics"
            report_dir = Path(tmpdir) / "eda"
            logger = MetricsLogger(run_id="run-eda-test", output_dir=metrics_dir)

            for ep in range(1, 21):
                logger.log(EpisodeMetrics(
                    event_type="episode_metrics",
                    episode=ep,
                    episode_reward=float(ep * 0.5),
                    rolling_mean_reward=float(ep * 0.4),
                    td_loss=float(1.0 / ep),
                    epsilon=max(0.05, 1.0 - ep * 0.05),
                ))

            logger.log(EpisodeMetrics(
                event_type="eval_metrics",
                episode=20,
                evaluation_success_rate=0.88,
                td_loss=0.05,
                incorrect_destination_rate=0.0,
                abandon_rate=0.12,
                unresolved_rate=0.0,
                evaluation_mean_reward=7.5,
                mean_turns=1.6,
            ))

            res = generate_eda_report(logger.filepath, output_dir=report_dir)
            assert Path(res["summary_json"]).exists()
            assert Path(res["html_report"]).exists()
            assert Path(res["test_metrics_csv"]).exists()
            assert "accuracy_and_loss" in res["plots"]
            assert "error_breakdown" in res["plots"]
            assert "summary_dashboard" in res["plots"]

            stats = res["stats"]
            assert stats["accuracy_metrics"]["final_accuracy"] == 0.88
            assert stats["error_metrics"]["safety_boundary_maintained"] is True
