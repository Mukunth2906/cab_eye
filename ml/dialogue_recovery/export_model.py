"""
export_model.py — Export trained DQN weights to JSON with verification vectors.

Exports model weights, architecture metadata, schema version, feature names,
action names, and test verification vectors to JSON for lightweight Kotlin/Android
FloatArray inference without requiring PyTorch on mobile devices.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import torch

if __package__ in (None, ""):
    import sys
    _pkg_dir = Path(__file__).resolve().parent
    if str(_pkg_dir.parent) not in sys.path:
        sys.path.insert(0, str(_pkg_dir.parent))
    __package__ = _pkg_dir.name

from dialogue_recovery.actions import (
    ACTION_COUNT,
    ACTION_NAMES,
    RecoveryAction,
    action_mask,
    select_action,
)
from dialogue_recovery.config import Config, default_config
from dialogue_recovery.dqn_model import DQNAgent, QNetwork
from dialogue_recovery.state_schema import (
    FEATURE_NAMES,
    STATE_DIM,
    DialogueState,
)

logger = logging.getLogger(__name__)


def export_dqn_to_json(
    agent_or_checkpoint_path: DQNAgent | str | Path,
    output_path: str | Path,
    metadata: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """
    Export DQN network weights and verification vectors to a JSON file.

    Parameters
    ----------
    agent_or_checkpoint_path : DQNAgent or str or Path
        Either an existing DQNAgent instance or path to a .pt checkpoint.
    output_path : str or Path
        Target path for the exported JSON file.
    metadata : dict, optional
        Additional metadata (e.g. training run ID, git commit, evaluation score).

    Returns
    -------
    dict
        The exported JSON dictionary.
    """
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    if isinstance(agent_or_checkpoint_path, (str, Path)):
        checkpoint_path = Path(agent_or_checkpoint_path)
        checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
        cfg_dict = checkpoint.get("config", {})
        config = Config.from_dict(cfg_dict) if cfg_dict else default_config()
        agent = DQNAgent(config)
        agent.load_checkpoint(checkpoint_path)
        net = agent.online_net
        hidden_dim = config.hidden_dim
    else:
        agent = agent_or_checkpoint_path
        net = agent.online_net
        hidden_dim = agent.config.hidden_dim

    net.eval()
    state_dim = STATE_DIM
    action_count = ACTION_COUNT

    # Extract weights and biases: fc1, fc2, fc3
    layers_data = []
    for name in ["fc1", "fc2", "fc3"]:
        layer = getattr(net, name)
        w = layer.weight.detach().cpu().numpy().tolist()
        b = layer.bias.detach().cpu().numpy().tolist()
        layers_data.append({
            "name": name,
            "weight": w,
            "bias": b,
            "in_features": layer.in_features,
            "out_features": layer.out_features,
        })

    # Checksum calculation for weights
    weights_dump = json.dumps([l["weight"] for l in layers_data] + [l["bias"] for l in layers_data], sort_keys=True)
    weights_sha256 = hashlib.sha256(weights_dump.encode("utf-8")).hexdigest()

    # Generate deterministic verification test vectors
    rng = np.random.default_rng(seed=42)
    sample_inputs: List[List[float]] = []
    sample_masks: List[List[float]] = []
    sample_outputs: List[List[float]] = []
    expected_actions: List[int] = []

    # Vector 1: Default baseline state
    s0 = DialogueState()
    sample_inputs.append(s0.to_vector().tolist())
    sample_masks.append(action_mask(s0).tolist())

    # Vector 2: Candidate present with high score
    s1 = DialogueState(
        attempt_number=1,
        best_match_score=0.85,
        candidate_exists=True,
        saved_places_available=True,
    )
    sample_inputs.append(s1.to_vector().tolist())
    sample_masks.append(action_mask(s1).tolist())

    # Vector 3: Multiple attempts, no candidate
    s2 = DialogueState(
        attempt_number=3,
        candidate_exists=False,
        saved_places_available=False,
        gate_no_candidate=True,
    )
    sample_inputs.append(s2.to_vector().tolist())
    sample_masks.append(action_mask(s2).tolist())

    # Vector 4: Random reproducible vector
    rand_v = rng.uniform(0.0, 1.0, size=(state_dim,)).astype(np.float32).tolist()
    sample_inputs.append(rand_v)
    sample_masks.append([1.0] * action_count)

    for inp, msk in zip(sample_inputs, sample_masks):
        with torch.no_grad():
            t_in = torch.tensor(inp, dtype=torch.float32).unsqueeze(0)
            q_out = net(t_in).squeeze(0).numpy()
            sample_outputs.append([float(round(float(v), 6)) for v in q_out])
            act = select_action(q_out, np.array(msk, dtype=np.float32))
            expected_actions.append(act)

    export_data: Dict[str, Any] = {
        "schema_version": 1,
        "algorithm": "double_dqn",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "weights_sha256": weights_sha256,
        "evidence_label": "SIMULATED ENVIRONMENT — NOT VALIDATED ON REAL RIDERS",
        "state_dim": state_dim,
        "hidden_dim": hidden_dim,
        "action_count": action_count,
        "feature_names": FEATURE_NAMES,
        "action_names": ACTION_NAMES,
        "layers": layers_data,
        "verification": {
            "description": "Deterministic test vectors for parity verification against Kotlin inference.",
            "test_inputs": sample_inputs,
            "test_masks": sample_masks,
            "expected_q_values": sample_outputs,
            "expected_actions": expected_actions,
        },
        "metadata": metadata or {},
    }

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(export_data, f, indent=2)

    logger.info("Exported DQN model to %s (SHA-256: %s)", output_path, weights_sha256[:12])
    return export_data


def pure_numpy_forward(
    state_vector: List[float],
    model_json: Dict[str, Any],
) -> List[float]:
    """
    Pure NumPy forward pass matching the exact logic of Kotlin FloatArray inference.
    Used for export parity verification.
    """
    x = np.array(state_vector, dtype=np.float32)
    layers = model_json["layers"]

    # Layer 1: ReLU(W1·s + b1)
    w1 = np.array(layers[0]["weight"], dtype=np.float32)
    b1 = np.array(layers[0]["bias"], dtype=np.float32)
    h1 = np.maximum(0.0, w1 @ x + b1)

    # Layer 2: ReLU(W2·h1 + b2)
    w2 = np.array(layers[1]["weight"], dtype=np.float32)
    b2 = np.array(layers[1]["bias"], dtype=np.float32)
    h2 = np.maximum(0.0, w2 @ h1 + b2)

    # Layer 3: Linear W3·h2 + b3
    w3 = np.array(layers[2]["weight"], dtype=np.float32)
    b3 = np.array(layers[2]["bias"], dtype=np.float32)
    q = w3 @ h2 + b3

    return q.tolist()


def verify_export_parity(
    model_json_path: str | Path,
    tolerance: float = 1e-4,
) -> bool:
    """
    Verify that exported JSON forward pass matches expected Q-values within tolerance.
    """
    with open(model_json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    test_inputs = data["verification"]["test_inputs"]
    expected_outputs = data["verification"]["expected_q_values"]
    test_masks = data["verification"].get("test_masks", [])
    expected_actions = data["verification"].get("expected_actions", [])

    for i, (inp, expected) in enumerate(zip(test_inputs, expected_outputs)):
        actual = pure_numpy_forward(inp, data)
        for act_val, exp_val in zip(actual, expected):
            diff = abs(act_val - exp_val)
            if diff > tolerance:
                raise ValueError(
                    f"Parity mismatch at test vector {i}: actual={act_val:.6f}, "
                    f"expected={exp_val:.6f}, diff={diff:.6e} > tolerance={tolerance}"
                )

        if test_masks and expected_actions:
            mask = np.array(test_masks[i], dtype=np.float32)
            act = select_action(np.array(actual, dtype=np.float32), mask)
            if act != expected_actions[i]:
                raise ValueError(
                    f"Action mismatch at test vector {i}: actual={act}, expected={expected_actions[i]}"
                )

    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Export DQN model to JSON")
    parser.add_argument("--checkpoint", type=str, default="artifacts/checkpoints/checkpoint_ep20000.pt")
    parser.add_argument("--output", type=str, default="artifacts/recovery_policy_v1.json")
    args = parser.parse_args()

    export_dqn_to_json(args.checkpoint, args.output)
    verify_export_parity(args.output)
    print(f"Model exported and parity verified: {args.output}")
