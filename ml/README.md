# Cab Eye — ML Subsystem

Voice Dialogue Recovery reinforcement learning package for the Cab Eye voice-first accessible cab-booking application.

## Quick Start

```bash
# 1. Install dependencies
pip install -r requirements.txt

# 2. Run the unit test suite
python main.py test

# 3. Run a quick end-to-end demo (trains 300 eps, benchmarks vs baselines, exports weights, verifies parity)
python main.py demo

# 4. Generate Exploratory Data Analysis (EDA) report & plots
python main.py eda

# 5. Start the interactive real-time web dashboard
python main.py serve --port 8000
# Open http://localhost:8000 in your browser
```

## Directory Structure
- `dialogue_recovery/`: Core package containing state schema, valid-action masking, reward function, replay buffer, Double DQN network, environment simulator, evaluator, and FastAPI dashboard.
  - `state_schema.py`: 16-feature normalized state vector definitions.
  - `actions.py`: 5 canonical recovery actions with precondition masking.
  - `reward.py`: Reward design and episode termination criteria.
  - `replay_buffer.py`: Uniform experience replay buffer.
  - `dqn_model.py`: Double DQN agent with Huber loss and target network.
  - `environment.py`: Simulated voice dialogue recovery environment.
  - `evaluate.py`: Multi-policy evaluation benchmark against FixedLadder, RandomValid, AlwaysEscalate.
  - `export_model.py`: Serializer to JSON weights with verification test vectors.
  - `eda.py`: Automated Exploratory Data Analysis engine generating charts, JSON summaries, and HTML reports.
  - `server.py`: FastAPI server + live WebSocket metrics dashboard.
  - `tests/`: Pytest suite with 31 deterministic tests.
- `main.py`: Unified CLI entry point (`test`, `train`, `evaluate`, `export`, `eda`, `serve`, `demo`).
- `requirements.txt`: Python package requirements.
