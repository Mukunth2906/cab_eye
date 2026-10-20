"""
dqn_model.py — Double DQN Q-network and agent.

Architecture: 16 → Dense(64, ReLU) → Dense(64, ReLU) → 5 linear Q-values
Total parameters: 5,573 (≈ 21.8 KiB at float32).

The online network is updated by gradient descent.
The target network is a delayed copy refreshed every N training steps.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any, Dict, Optional

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from .actions import ACTION_COUNT, ACTION_NAMES, apply_mask, select_action
from .config import Config
from .state_schema import STATE_DIM


class QNetwork(nn.Module):
    """
    Small MLP Q-network: state_dim → hidden → hidden → action_count.

    Forward pass:
        h1 = ReLU(W1·s + b1)
        h2 = ReLU(W2·h1 + b2)
        Q(s, ·) = W3·h2 + b3

    Output is NOT probabilities — Q-values can be negative or > 1.
    """

    def __init__(self, state_dim: int = STATE_DIM, hidden_dim: int = 64,
                 action_count: int = ACTION_COUNT):
        super().__init__()
        self.fc1 = nn.Linear(state_dim, hidden_dim)
        self.fc2 = nn.Linear(hidden_dim, hidden_dim)
        self.fc3 = nn.Linear(hidden_dim, action_count)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """
        Args:
            x: (batch, state_dim) float tensor.
        Returns:
            Q-values: (batch, action_count) float tensor.
        """
        h1 = F.relu(self.fc1(x))
        h2 = F.relu(self.fc2(h1))
        return self.fc3(h2)


def init_weights(model: QNetwork, method: str = "kaiming") -> None:
    """Initialize weights. He/Kaiming for ReLU layers."""
    for module in model.modules():
        if isinstance(module, nn.Linear):
            if method == "kaiming":
                nn.init.kaiming_normal_(module.weight, nonlinearity="relu")
            else:
                nn.init.xavier_normal_(module.weight)
            nn.init.zeros_(module.bias)


class DQNAgent:
    """
    Double DQN agent with action masking, target network, and epsilon schedule.

    Double DQN target:
        a*_next = argmax_{a' in valid(s')} Q_online(s', a')
        y = r                               if done
        y = r + γ · Q_target(s', a*_next)    if not done
    """

    def __init__(self, config: Config, device: Optional[torch.device] = None):
        self.config = config
        self.device = device or torch.device("cpu")

        # Online and target networks
        self.online_net = QNetwork(
            state_dim=config.state_dim,
            hidden_dim=config.hidden_dim,
            action_count=config.action_count,
        ).to(self.device)
        init_weights(self.online_net, config.weight_init)

        self.target_net = QNetwork(
            state_dim=config.state_dim,
            hidden_dim=config.hidden_dim,
            action_count=config.action_count,
        ).to(self.device)
        self.target_net.load_state_dict(self.online_net.state_dict())
        self.target_net.eval()

        # Optimizer
        self.optimizer = torch.optim.Adam(
            self.online_net.parameters(), lr=config.learning_rate
        )

        # Epsilon schedule
        self.epsilon = config.epsilon_start

        # Training step counter
        self.train_steps = 0

    # ── Action selection ────────────────────────────────────────────────

    def select_action(
        self, state: np.ndarray, mask: np.ndarray, explore: bool = True
    ) -> int:
        """
        Epsilon-greedy action selection over valid actions.

        During training (explore=True): random valid action with prob epsilon.
        During evaluation / inference (explore=False): greedy over valid actions.
        """
        if explore and np.random.random() < self.epsilon:
            # Random valid action
            valid_indices = np.where(mask > 0)[0]
            if len(valid_indices) == 0:
                return 2  # OFFER_ESCAPES fallback
            return int(np.random.choice(valid_indices))

        # Greedy
        with torch.no_grad():
            state_t = torch.tensor(state, dtype=torch.float32, device=self.device).unsqueeze(0)
            q_values = self.online_net(state_t).cpu().numpy()[0]

        return select_action(q_values, mask)

    # ── Training step ───────────────────────────────────────────────────

    def train_step(
        self,
        states: torch.Tensor,
        actions: torch.Tensor,
        rewards: torch.Tensor,
        next_states: torch.Tensor,
        dones: torch.Tensor,
        masks: torch.Tensor,
        next_masks: torch.Tensor,
    ) -> float:
        """
        One gradient step of Double DQN with Huber loss.

        Returns the scalar loss value.
        """
        self.online_net.train()

        # Current Q-values for chosen actions
        q_values = self.online_net(states)                          # (B, A)
        q_selected = q_values.gather(1, actions)                    # (B, 1)

        with torch.no_grad():
            # Double DQN: online net selects, target net evaluates
            next_q_online = self.online_net(next_states)            # (B, A)

            # Mask invalid next-state actions to -inf
            invalid_next = (next_masks == 0)
            next_q_online = next_q_online.masked_fill(invalid_next.bool(), -1e9)

            next_actions = next_q_online.argmax(dim=1, keepdim=True)  # (B, 1)

            next_q_target = self.target_net(next_states)              # (B, A)
            next_q_selected = next_q_target.gather(1, next_actions)   # (B, 1)

            # TD target: y = r if done, else r + gamma * Q_target(s', a*_next)
            targets = rewards + (1.0 - dones) * self.config.gamma * next_q_selected

        # Huber loss
        loss = F.huber_loss(q_selected, targets, delta=self.config.huber_delta)

        self.optimizer.zero_grad()
        loss.backward()
        self.optimizer.step()

        self.train_steps += 1

        # Refresh target network periodically
        if self.train_steps % self.config.target_network_refresh == 0:
            self.target_net.load_state_dict(self.online_net.state_dict())

        return loss.item()

    # ── Epsilon decay ───────────────────────────────────────────────────

    def decay_epsilon(self) -> None:
        """Decay epsilon by the configured multiplicative factor."""
        self.epsilon = max(
            self.config.epsilon_min,
            self.epsilon * self.config.epsilon_decay,
        )

    # ── Checkpointing ───────────────────────────────────────────────────

    def save_checkpoint(self, path: str | Path) -> None:
        """Save online and target network weights, optimizer, and epsilon."""
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        torch.save(
            {
                "online_state_dict": self.online_net.state_dict(),
                "target_state_dict": self.target_net.state_dict(),
                "optimizer_state_dict": self.optimizer.state_dict(),
                "epsilon": self.epsilon,
                "train_steps": self.train_steps,
                "config": self.config.to_dict(),
            },
            path,
        )

    def load_checkpoint(self, path: str | Path) -> None:
        """Restore from a checkpoint."""
        data = torch.load(path, map_location=self.device, weights_only=False)
        self.online_net.load_state_dict(data["online_state_dict"])
        self.target_net.load_state_dict(data["target_state_dict"])
        self.optimizer.load_state_dict(data["optimizer_state_dict"])
        self.epsilon = data["epsilon"]
        self.train_steps = data["train_steps"]

    # ── Export for Kotlin inference ──────────────────────────────────────

    def export_weights(self, path: str | Path) -> None:
        """
        Export model weights to JSON for lightweight Kotlin FloatArray inference.

        Format:
        {
          "schema_version": 1,
          "state_dim": 16,
          "hidden_dim": 64,
          "action_count": 5,
          "action_names": [...],
          "layers": [
            {"weight": [[...], ...], "bias": [...]},  # fc1
            {"weight": [[...], ...], "bias": [...]},  # fc2
            {"weight": [[...], ...], "bias": [...]}   # fc3
          ]
        }
        """
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)

        sd = self.online_net.state_dict()
        export = {
            "schema_version": 1,
            "state_dim": self.config.state_dim,
            "hidden_dim": self.config.hidden_dim,
            "action_count": self.config.action_count,
            "action_names": ACTION_NAMES,
            "algorithm": self.config.algorithm,
            "environment_kind": self.config.environment_kind,
            "evidence_label": "SIMULATED ENVIRONMENT — NOT VALIDATED ON REAL RIDERS",
            "layers": [],
        }

        for name in ["fc1", "fc2", "fc3"]:
            w = sd[f"{name}.weight"].cpu().numpy().tolist()
            b = sd[f"{name}.bias"].cpu().numpy().tolist()
            export["layers"].append({"weight": w, "bias": b})

        with open(path, "w") as f:
            json.dump(export, f, indent=2)

    @staticmethod
    def count_parameters(model: nn.Module) -> int:
        """Count total trainable parameters."""
        return sum(p.numel() for p in model.parameters() if p.requires_grad)
