"""
test_all.py — Run all DQN dialogue recovery tests.

Usage:
    python -m pytest dialogue_recovery/tests/test_all.py -v
"""

from dialogue_recovery.tests import (
    TestStateSchema,
    TestActionMasks,
    TestReward,
    TestReplayBuffer,
    TestQNetwork,
    TestDoubleDQNTarget,
    TestEnvironment,
    TestReproducibility,
    TestExport,
    TestMetrics,
    TestEvaluation,
    TestEDA,
)
