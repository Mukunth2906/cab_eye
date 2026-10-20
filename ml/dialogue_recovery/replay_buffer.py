"""
replay_buffer.py — Experience replay for DQN training.

Stores (state, action, reward, next_state, done, mask, next_mask) transitions
and samples uniform random mini-batches to reduce correlation.
"""

from __future__ import annotations

import random
from collections import deque
from typing import NamedTuple

import numpy as np
import torch


class Transition(NamedTuple):
    """A single experience transition."""
    state: np.ndarray       # (state_dim,)
    action: int
    reward: float
    next_state: np.ndarray  # (state_dim,)
    done: bool
    mask: np.ndarray        # (action_count,) valid-action mask
    next_mask: np.ndarray   # (action_count,) next-state valid-action mask


class Batch(NamedTuple):
    """A mini-batch of transitions on the target device."""
    states: torch.Tensor       # (B, state_dim)
    actions: torch.Tensor      # (B, 1) long
    rewards: torch.Tensor      # (B, 1)
    next_states: torch.Tensor  # (B, state_dim)
    dones: torch.Tensor        # (B, 1)
    masks: torch.Tensor        # (B, action_count)
    next_masks: torch.Tensor   # (B, action_count)


class ReplayBuffer:
    """Fixed-capacity circular replay buffer with uniform sampling."""

    def __init__(self, capacity: int, seed: int | None = None):
        self.buffer: deque[Transition] = deque(maxlen=capacity)
        self.rng = random.Random(seed)

    def push(self, transition: Transition) -> None:
        self.buffer.append(transition)

    def sample(self, batch_size: int, device: torch.device | None = None) -> Batch:
        """Sample a random mini-batch and return it as tensors."""
        if device is None:
            device = torch.device("cpu")

        transitions = self.rng.sample(list(self.buffer), min(batch_size, len(self.buffer)))

        states = torch.tensor(
            np.array([t.state for t in transitions]), dtype=torch.float32, device=device
        )
        actions = torch.tensor(
            [[t.action] for t in transitions], dtype=torch.long, device=device
        )
        rewards = torch.tensor(
            [[t.reward] for t in transitions], dtype=torch.float32, device=device
        )
        next_states = torch.tensor(
            np.array([t.next_state for t in transitions]), dtype=torch.float32, device=device
        )
        dones = torch.tensor(
            [[1.0 if t.done else 0.0] for t in transitions], dtype=torch.float32, device=device
        )
        masks = torch.tensor(
            np.array([t.mask for t in transitions]), dtype=torch.float32, device=device
        )
        next_masks = torch.tensor(
            np.array([t.next_mask for t in transitions]), dtype=torch.float32, device=device
        )

        return Batch(states, actions, rewards, next_states, dones, masks, next_masks)

    def __len__(self) -> int:
        return len(self.buffer)

    @property
    def capacity(self) -> int:
        return self.buffer.maxlen  # type: ignore[return-value]
