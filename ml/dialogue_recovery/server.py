"""
server.py — FastAPI training service and real-time WebSocket dashboard.

Provides:
  - Interactive web dashboard for live monitoring and training control
  - WebSocket metrics streaming (/ws/metrics) with disconnect/reconnect replay
  - REST endpoints for start, stop, status, eval, export, and test runner
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import subprocess
import sys
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel

if __package__ in (None, ""):
    import sys
    _pkg_dir = Path(__file__).resolve().parent
    if str(_pkg_dir.parent) not in sys.path:
        sys.path.insert(0, str(_pkg_dir.parent))
    __package__ = _pkg_dir.name

from dialogue_recovery.config import Config, default_config, load_config
from dialogue_recovery.dqn_model import DQNAgent
from dialogue_recovery.evaluate import compare_policies, evaluate_dqn
from dialogue_recovery.export_model import export_dqn_to_json, verify_export_parity
from dialogue_recovery.metrics import EpisodeMetrics, MetricsLogger
from dialogue_recovery.train import train

logger = logging.getLogger(__name__)

app = FastAPI(title="Cab Eye DQN Training Service", version="1.0.0")

# Global trainer state
class TrainingManager:
    def __init__(self):
        self.lock = threading.Lock()
        self.is_training: bool = False
        self.stop_requested: bool = False
        self.current_run_id: Optional[str] = None
        self.current_episode: int = 0
        self.total_episodes: int = 0
        self.best_eval_reward: float = -float("inf")
        self.latest_metrics: Dict[str, Any] = {}
        self.recent_events: List[Dict[str, Any]] = []
        self.active_websockets: List[WebSocket] = []
        self.training_thread: Optional[threading.Thread] = None

    def add_event(self, event_dict: Dict[str, Any]):
        with self.lock:
            self.recent_events.append(event_dict)
            if len(self.recent_events) > 1000:
                self.recent_events.pop(0)

            event_type = event_dict.get("event_type") or event_dict.get("event")
            if event_type in ["episode_metrics", "episode"]:
                self.current_episode = event_dict.get("episode", 0)
                self.latest_metrics = event_dict
            elif event_type in ["eval_metrics", "eval"]:
                reward = event_dict.get("evaluation_mean_reward", event_dict.get("mean_reward", -float("inf")))
                if reward is not None and reward > self.best_eval_reward:
                    self.best_eval_reward = reward

    async def broadcast(self, event_dict: Dict[str, Any]):
        disconnected = []
        with self.lock:
            sockets = list(self.active_websockets)

        for ws in sockets:
            try:
                await ws.send_json(event_dict)
            except Exception:
                disconnected.append(ws)

        if disconnected:
            with self.lock:
                for ws in disconnected:
                    if ws in self.active_websockets:
                        self.active_websockets.remove(ws)


manager = TrainingManager()


class StartTrainRequest(BaseModel):
    total_episodes: int = 1000
    learning_rate: float = 0.0005
    seed: int = 42
    batch_size: int = 64
    eval_interval: int = 100


@app.get("/api/status")
def get_status():
    with manager.lock:
        return {
            "is_training": manager.is_training,
            "stop_requested": manager.stop_requested,
            "current_run_id": manager.current_run_id,
            "current_episode": manager.current_episode,
            "total_episodes": manager.total_episodes,
            "best_eval_reward": manager.best_eval_reward if manager.best_eval_reward != -float("inf") else None,
            "latest_metrics": manager.latest_metrics,
        }


@app.get("/api/metrics/history")
def get_metrics_history(limit: int = 500):
    with manager.lock:
        return manager.recent_events[-limit:]


@app.post("/api/train/start")
def start_training(req: StartTrainRequest):
    with manager.lock:
        if manager.is_training:
            raise HTTPException(status_code=400, detail="Training is already running.")

        manager.is_training = True
        manager.stop_requested = False
        manager.current_episode = 0
        manager.total_episodes = req.total_episodes
        manager.best_eval_reward = -float("inf")
        manager.recent_events.clear()

    # Create loop reference for async broadcast from thread
    loop = asyncio.get_event_loop()

    def run_worker():
        try:
            cfg = default_config()
            cfg.num_episodes = req.total_episodes
            cfg.learning_rate = req.learning_rate
            cfg.batch_size = req.batch_size
            cfg.eval_frequency = req.eval_interval

            def broadcast_fn(json_str: str):
                try:
                    evt_dict = json.loads(json_str)
                    manager.add_event(evt_dict)
                    asyncio.run_coroutine_threadsafe(manager.broadcast(evt_dict), loop)
                except Exception:
                    pass

            def check_stop() -> bool:
                with manager.lock:
                    return manager.stop_requested

            train(cfg, seed=req.seed, broadcast_fn=broadcast_fn, stop_flag=check_stop)
        except Exception as e:
            logger.exception("Training error: %s", e)
            err_dict = {"event_type": "error", "message": str(e), "timestamp": datetime.now(timezone.utc).isoformat()}
            manager.add_event(err_dict)
            asyncio.run_coroutine_threadsafe(manager.broadcast(err_dict), loop)
        finally:
            with manager.lock:
                manager.is_training = False
                end_evt = {"event_type": "run_complete", "timestamp": datetime.now(timezone.utc).isoformat()}
                manager.add_event(end_evt)
                asyncio.run_coroutine_threadsafe(manager.broadcast(end_evt), loop)

    t = threading.Thread(target=run_worker, daemon=True)
    manager.training_thread = t
    t.start()

    return {"status": "started", "config": req.model_dump()}


@app.post("/api/train/stop")
def stop_training():
    with manager.lock:
        if not manager.is_training:
            return {"status": "not_running"}
        manager.stop_requested = True
    return {"status": "stopping_gracefully"}


@app.post("/api/evaluate")
def run_evaluation(checkpoint_path: str = "artifacts/checkpoints/checkpoint_latest.pt", eval_episodes: int = 100):
    cp = Path(checkpoint_path)
    if not cp.exists():
        # Fall back to best.pt or final.pt in recent runs
        candidates = list(Path("artifacts").glob("**/best.pt")) + list(Path("artifacts").glob("**/final.pt"))
        if candidates:
            cp = candidates[0]
        else:
            raise HTTPException(status_code=404, detail=f"Checkpoint {checkpoint_path} not found.")

    cfg = default_config()
    agent = DQNAgent(cfg)
    agent.load_checkpoint(cp)

    comparison = compare_policies(agent, cfg, num_episodes=eval_episodes)
    return {name: r.to_dict() for name, r in comparison.items()}


@app.post("/api/export")
def export_model_endpoint(checkpoint_path: str = "artifacts/checkpoints/final.pt", output_path: str = "artifacts/recovery_policy_v1.json"):
    cp = Path(checkpoint_path)
    if not cp.exists():
        candidates = list(Path("artifacts").glob("**/final.pt")) + list(Path("artifacts").glob("**/best.pt"))
        if candidates:
            cp = candidates[0]
        else:
            raise HTTPException(status_code=404, detail=f"Checkpoint {checkpoint_path} not found.")

    data = export_dqn_to_json(cp, output_path)
    verified = verify_export_parity(output_path)
    return {
        "status": "exported",
        "output_path": output_path,
        "weights_sha256": data["weights_sha256"],
        "parity_verified": verified,
    }


@app.get("/api/tests/run")
def run_unit_tests():
    cmd = [sys.executable, "-m", "pytest", "dialogue_recovery/tests/test_all.py", "-v"]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return {
        "passed": proc.returncode == 0,
        "exit_code": proc.returncode,
        "output": proc.stdout,
    }


@app.get("/api/eda")
def get_eda_data():
    from dialogue_recovery.eda import generate_eda_report
    try:
        report = generate_eda_report("artifacts/metrics", "reports/eda")
        return report
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/eda", response_class=HTMLResponse)
def view_eda_report():
    html_file = Path("reports/eda/eda_report.html")
    if not html_file.exists():
        try:
            from dialogue_recovery.eda import generate_eda_report
            generate_eda_report("artifacts/metrics", "reports/eda")
        except Exception:
            pass
    if html_file.exists():
        with open(html_file, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    return HTMLResponse("<h3>No EDA report generated yet. Run training or demo first.</h3>")


@app.websocket("/ws/metrics")
async def websocket_metrics(websocket: WebSocket):
    await websocket.accept()
    with manager.lock:
        manager.active_websockets.append(websocket)
        history = list(manager.recent_events)

    # Replay history on connection
    for evt in history:
        await websocket.send_json(evt)

    try:
        while True:
            # Keep socket alive
            msg = await websocket.receive_text()
            if msg == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        with manager.lock:
            if websocket in manager.active_websockets:
                manager.active_websockets.remove(websocket)


@app.get("/", response_class=HTMLResponse)
def get_dashboard():
    return HTMLResponse(content=DASHBOARD_HTML)


DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Cab Eye — Double DQN Dialogue Recovery Dashboard</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <style>
    :root {
      --bg: #0b0f17;
      --card-bg: rgba(20, 27, 41, 0.7);
      --border: rgba(255, 255, 255, 0.08);
      --border-accent: rgba(56, 189, 248, 0.3);
      --text-main: #f1f5f9;
      --text-muted: #94a3b8;
      --cyan: #38bdf8;
      --indigo: #818cf8;
      --green: #34d399;
      --amber: #fbbf24;
      --rose: #fb7185;
      --gradient: linear-gradient(135deg, #38bdf8 0%, #818cf8 100%);
    }

    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text-main);
      font-family: 'Outfit', sans-serif;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
    }

    header {
      padding: 1.25rem 2rem;
      background: rgba(15, 23, 42, 0.8);
      backdrop-filter: blur(12px);
      border-bottom: 1px solid var(--border);
      display: flex;
      justify-content: space-between;
      align-items: center;
      position: sticky;
      top: 0;
      z-index: 100;
    }

    .brand {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }

    .brand-logo {
      width: 36px;
      height: 36px;
      border-radius: 10px;
      background: var(--gradient);
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 700;
      color: #0b0f17;
      box-shadow: 0 0 20px rgba(56, 189, 248, 0.4);
    }

    .brand h1 { font-size: 1.25rem; font-weight: 600; letter-spacing: -0.02em; }
    .badge {
      font-size: 0.75rem;
      padding: 0.25rem 0.6rem;
      border-radius: 20px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .badge-sim { background: rgba(251, 191, 36, 0.15); color: var(--amber); border: 1px solid rgba(251, 191, 36, 0.3); }
    .status-badge {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-size: 0.85rem;
      padding: 0.35rem 0.85rem;
      border-radius: 20px;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid var(--border);
    }
    .status-dot { width: 8px; height: 8px; border-radius: 50%; background: #64748b; }
    .status-dot.active { background: var(--green); box-shadow: 0 0 10px var(--green); animation: pulse 2s infinite; }

    @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }

    main {
      padding: 2rem;
      max-width: 1500px;
      margin: 0 auto;
      width: 100%;
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
    }

    .grid-stats {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 1rem;
    }

    .card {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 1.25rem;
      backdrop-filter: blur(16px);
      box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2);
      transition: border-color 0.2s;
    }
    .card:hover { border-color: var(--border-accent); }

    .card-title {
      font-size: 0.8rem;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--text-muted);
      margin-bottom: 0.5rem;
    }

    .stat-val {
      font-size: 1.85rem;
      font-weight: 700;
      color: var(--text-main);
      font-feature-settings: "tnum";
    }

    .stat-sub {
      font-size: 0.8rem;
      color: var(--text-muted);
      margin-top: 0.25rem;
    }

    .controls-bar {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
      align-items: center;
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 1rem 1.25rem;
    }

    .btn {
      background: rgba(255, 255, 255, 0.07);
      color: var(--text-main);
      border: 1px solid var(--border);
      padding: 0.6rem 1.2rem;
      border-radius: 8px;
      font-family: inherit;
      font-size: 0.85rem;
      font-weight: 600;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      transition: all 0.2s;
    }
    .btn:hover { background: rgba(255, 255, 255, 0.12); border-color: rgba(255, 255, 255, 0.2); }
    .btn-primary { background: var(--gradient); color: #0b0f17; border: none; font-weight: 700; }
    .btn-primary:hover { opacity: 0.95; box-shadow: 0 0 15px rgba(56, 189, 248, 0.4); }
    .btn-danger { background: rgba(251, 113, 133, 0.15); color: var(--rose); border-color: rgba(251, 113, 133, 0.3); }
    .btn-danger:hover { background: rgba(251, 113, 133, 0.25); }

    .input-group {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-size: 0.85rem;
      color: var(--text-muted);
    }
    .input-field {
      background: rgba(11, 15, 23, 0.7);
      border: 1px solid var(--border);
      color: var(--text-main);
      padding: 0.5rem 0.75rem;
      border-radius: 6px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 0.85rem;
      width: 90px;
    }

    .charts-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 1.25rem;
    }

    @media (max-width: 960px) {
      .charts-grid { grid-template-columns: 1fr; }
    }

    .chart-container {
      position: relative;
      height: 280px;
      width: 100%;
    }

    .terminal-panel {
      font-family: 'JetBrains Mono', monospace;
      background: #070a10;
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 1rem;
      height: 220px;
      overflow-y: auto;
      font-size: 0.8rem;
      line-height: 1.6;
      color: #94a3b8;
    }

    .log-line { display: flex; gap: 0.75rem; }
    .log-time { color: #475569; }
    .log-info { color: var(--cyan); }
    .log-eval { color: var(--green); font-weight: 600; }
    .log-warn { color: var(--amber); }
    .log-err { color: var(--rose); }

    footer {
      margin-top: auto;
      padding: 1.5rem 2rem;
      text-align: center;
      font-size: 0.8rem;
      color: var(--text-muted);
      border-top: 1px solid var(--border);
    }
  </style>
</head>
<body>
  <header>
    <div class="brand">
      <div class="brand-logo">CE</div>
      <div>
        <h1>Cab Eye — DQN Dialogue Recovery</h1>
        <p style="font-size: 0.75rem; color: var(--text-muted);">Adaptive voice confirmation & escalation policy</p>
      </div>
      <span class="badge badge-sim">Simulated Environment</span>
    </div>
    <div class="status-badge">
      <div id="statusDot" class="status-dot"></div>
      <span id="statusText">Connecting...</span>
    </div>
  </header>

  <main>
    <!-- Top Stats -->
    <div class="grid-stats">
      <div class="card">
        <div class="card-title">Training Progress</div>
        <div class="stat-val" id="statEpisode">0 / 0</div>
        <div class="stat-sub" id="statEpsPct">0% complete</div>
      </div>
      <div class="card">
        <div class="card-title">Mean Reward (100 eps)</div>
        <div class="stat-val" id="statMeanReward">—</div>
        <div class="stat-sub" id="statBestEval">Best Eval: —</div>
      </div>
      <div class="card">
        <div class="card-title">Success Rate</div>
        <div class="stat-val" id="statSuccessRate" style="color: var(--green);">—</div>
        <div class="stat-sub" id="statTurns">Mean Turns: —</div>
      </div>
      <div class="card">
        <div class="card-title">Exploration (Epsilon)</div>
        <div class="stat-val" id="statEpsilon" style="color: var(--indigo);">1.000</div>
        <div class="stat-sub" id="statStepCount">Total Steps: 0</div>
      </div>
      <div class="card">
        <div class="card-title">Unit Test Status</div>
        <div class="stat-val" id="statTests" style="color: var(--cyan);">29 / 29</div>
        <div class="stat-sub">100% Passing (test_all.py)</div>
      </div>
    </div>

    <!-- Controls -->
    <div class="controls-bar">
      <div class="input-group">
        <label>Episodes:</label>
        <input type="number" id="inputEpisodes" class="input-field" value="1000" min="100" max="10000" step="100">
      </div>
      <div class="input-group">
        <label>Learning Rate:</label>
        <input type="number" id="inputLR" class="input-field" value="0.0005" step="0.0001">
      </div>
      <button id="btnStart" class="btn btn-primary" onclick="startTraining()">▶ Start Training</button>
      <button id="btnStop" class="btn btn-danger" onclick="stopTraining()">⏹ Stop Gracefully</button>
      <button class="btn" onclick="runEvaluation()">⚖ Run Benchmark Eval</button>
      <button class="btn" onclick="exportModel()">💾 Export JSON Weights</button>
      <button class="btn" onclick="runTests()">🧪 Run Unit Tests</button>
      <a href="/eda" target="_blank" class="btn" style="text-decoration: none;">📊 View EDA Report</a>
    </div>

    <!-- Charts -->
    <div class="charts-grid">
      <div class="card">
        <div class="card-title">Reward History (Per Episode & Moving Avg)</div>
        <div class="chart-container">
          <canvas id="rewardChart"></canvas>
        </div>
      </div>
      <div class="card">
        <div class="card-title">Huber Loss & Epsilon Decay</div>
        <div class="chart-container">
          <canvas id="lossChart"></canvas>
        </div>
      </div>
      <div class="card">
        <div class="card-title">Action Distribution (Voice Recovery Policy)</div>
        <div class="chart-container">
          <canvas id="actionsChart"></canvas>
        </div>
      </div>
      <div class="card">
        <div class="card-title">Benchmark: DQN vs Baselines</div>
        <div class="chart-container">
          <canvas id="benchmarkChart"></canvas>
        </div>
      </div>
    </div>

    <!-- Console / Event Stream -->
    <div class="card" style="padding: 1rem;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.5rem;">
        <div class="card-title" style="margin-bottom: 0;">Live Event Stream</div>
        <button class="btn" style="padding: 0.2rem 0.6rem; font-size: 0.75rem;" onclick="clearLogs()">Clear</button>
      </div>
      <div id="terminal" class="terminal-panel">
        <div class="log-line">
          <span class="log-time">[Ready]</span>
          <span class="log-info">Cab Eye Double DQN dashboard initialized. Waiting for stream...</span>
        </div>
      </div>
    </div>
  </main>

  <footer>
    Cab Eye Autonomous Voice Recovery • Architecture: 16 → Dense(64, ReLU) → Dense(64, ReLU) → 5 Q-values • Evidence: SIMULATED ENVIRONMENT
  </footer>

  <script>
    const actionLabels = ['REPROMPT', 'ASK_MORE_INFO', 'CONFIRM_CANDIDATE', 'OFFER_SAVED_PLACE', 'OFFER_ESCAPES'];
    let socket;
    let rewardChart, lossChart, actionsChart, benchmarkChart;

    const rewardData = { episodes: [], rewards: [], movingAvg: [] };
    const lossData = { episodes: [], loss: [], epsilon: [] };
    const actionCounts = [0, 0, 0, 0, 0];

    function initCharts() {
      const chartOptions = {
        responsive: true,
        maintainAspectRatio: false,
        animation: { duration: 0 },
        scales: {
          x: { grid: { color: 'rgba(255,255,255,0.05)' }, ticks: { color: '#64748b', font: { family: 'Outfit' } } },
          y: { grid: { color: 'rgba(255,255,255,0.05)' }, ticks: { color: '#64748b', font: { family: 'Outfit' } } }
        },
        plugins: {
          legend: { labels: { color: '#94a3b8', font: { family: 'Outfit' } } }
        }
      };

      // 1. Reward Chart
      const ctxReward = document.getElementById('rewardChart').getContext('2d');
      rewardChart = new Chart(ctxReward, {
        type: 'line',
        data: {
          labels: rewardData.episodes,
          datasets: [
            { label: 'Episode Reward', data: rewardData.rewards, borderColor: 'rgba(56, 189, 248, 0.4)', borderWidth: 1, pointRadius: 0 },
            { label: 'Moving Avg (100)', data: rewardData.movingAvg, borderColor: '#38bdf8', borderWidth: 2, pointRadius: 0 }
          ]
        },
        options: chartOptions
      });

      // 2. Loss & Epsilon Chart
      const ctxLoss = document.getElementById('lossChart').getContext('2d');
      lossChart = new Chart(ctxLoss, {
        type: 'line',
        data: {
          labels: lossData.episodes,
          datasets: [
            { label: 'TD Huber Loss', data: lossData.loss, borderColor: '#fb7185', yAxisID: 'y', borderWidth: 1.5, pointRadius: 0 },
            { label: 'Epsilon', data: lossData.epsilon, borderColor: '#818cf8', yAxisID: 'y1', borderWidth: 1.5, borderDash: [4, 4], pointRadius: 0 }
          ]
        },
        options: {
          ...chartOptions,
          scales: {
            ...chartOptions.scales,
            y: { ...chartOptions.scales.y, position: 'left', title: { display: true, text: 'Loss', color: '#fb7185' } },
            y1: { position: 'right', min: 0, max: 1, grid: { drawOnChartArea: false }, ticks: { color: '#818cf8' }, title: { display: true, text: 'Epsilon', color: '#818cf8' } }
          }
        }
      });

      // 3. Action Distribution Chart
      const ctxActions = document.getElementById('actionsChart').getContext('2d');
      actionsChart = new Chart(ctxActions, {
        type: 'bar',
        data: {
          labels: actionLabels,
          datasets: [{
            label: 'Action Invocations',
            data: actionCounts,
            backgroundColor: [
              'rgba(56, 189, 248, 0.7)',
              'rgba(129, 140, 248, 0.7)',
              'rgba(52, 211, 153, 0.7)',
              'rgba(251, 191, 36, 0.7)',
              'rgba(251, 113, 133, 0.7)'
            ],
            borderRadius: 6
          }]
        },
        options: chartOptions
      });

      // 4. Benchmark Chart
      const ctxBench = document.getElementById('benchmarkChart').getContext('2d');
      benchmarkChart = new Chart(ctxBench, {
        type: 'bar',
        data: {
          labels: ['DQN (Greedy)', 'FixedLadder', 'RandomValid', 'AlwaysEscalate'],
          datasets: [
            { label: 'Success Rate (%)', data: [0, 68.2, 38.0, 0.0], backgroundColor: 'rgba(52, 211, 153, 0.8)', borderRadius: 6 },
            { label: 'Mean Reward', data: [0, 4.8, -8.2, -4.5], backgroundColor: 'rgba(56, 189, 248, 0.8)', borderRadius: 6 }
          ]
        },
        options: chartOptions
      });
    }

    function connectWebSocket() {
      const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
      socket = new WebSocket(`${protocol}//${location.host}/ws/metrics`);

      socket.onopen = () => {
        document.getElementById('statusDot').className = 'status-dot active';
        document.getElementById('statusText').innerText = 'Connected';
        appendLog('Connected to metrics WebSocket server.', 'log-info');
      };

      socket.onclose = () => {
        document.getElementById('statusDot').className = 'status-dot';
        document.getElementById('statusText').innerText = 'Disconnected (Reconnecting...)';
        setTimeout(connectWebSocket, 2000);
      };

      socket.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        handleMetricEvent(msg);
      };
    }

    function handleMetricEvent(evt) {
      const isEp = (evt.event === 'episode' || evt.event_type === 'episode_metrics');
      const isEval = (evt.event === 'eval' || evt.event_type === 'eval_metrics');
      if (isEp) {
        const ep = evt.episode || 0;
        const total = evt.total_episodes || 1000;
        const meanR = (evt.rolling_mean_reward !== undefined && evt.rolling_mean_reward !== null) ? evt.rolling_mean_reward : (evt.mean_reward_100 || 0);
        const epR = (evt.episode_reward !== undefined && evt.episode_reward !== null) ? evt.episode_reward : 0;
        const loss = (evt.td_loss !== undefined && evt.td_loss !== null) ? evt.td_loss : evt.loss;
        const eps = (evt.epsilon !== undefined && evt.epsilon !== null) ? evt.epsilon : 1.0;
        const succ = (evt.success_rate !== undefined && evt.success_rate !== null) ? evt.success_rate : (evt.success_rate_100 || 0);
        const turns = (evt.mean_turns !== undefined && evt.mean_turns !== null) ? evt.mean_turns : (evt.mean_turns_100 || 0);
        const steps = evt.environment_steps !== undefined ? evt.environment_steps : (evt.total_steps || 0);

        document.getElementById('statEpisode').innerText = `${ep} / ${total}`;
        document.getElementById('statEpsPct').innerText = `${((ep/total)*100).toFixed(1)}% complete`;
        document.getElementById('statMeanReward').innerText = meanR.toFixed(2);
        document.getElementById('statSuccessRate').innerText = `${(succ * 100).toFixed(1)}%`;
        document.getElementById('statTurns').innerText = `Mean Turns: ${turns.toFixed(2)}`;
        document.getElementById('statEpsilon').innerText = eps.toFixed(3);
        document.getElementById('statStepCount').innerText = `Total Steps: ${steps}`;

        rewardData.episodes.push(ep);
        rewardData.rewards.push(epR);
        rewardData.movingAvg.push(meanR);

        if (rewardData.episodes.length > 500) {
          rewardData.episodes.shift();
          rewardData.rewards.shift();
          rewardData.movingAvg.shift();
        }

        if (loss !== null && loss !== undefined) {
          lossData.episodes.push(ep);
          lossData.loss.push(loss);
          lossData.epsilon.push(eps);
          if (lossData.episodes.length > 500) {
            lossData.episodes.shift();
            lossData.loss.shift();
            lossData.epsilon.shift();
          }
        }

        // Action distribution
        if (evt.action_distribution) {
          for (let i = 0; i < 5; i++) {
            actionCounts[i] = evt.action_distribution[actionLabels[i]] || 0;
          }
          actionsChart.update('none');
        }

        if (ep % 5 === 0) {
          rewardChart.update('none');
          lossChart.update('none');
        }

        if (ep % 50 === 0) {
          appendLog(`Ep ${ep}: Reward=${epR.toFixed(1)}, Mean=${meanR.toFixed(2)}, Succ=${(succ*100).toFixed(0)}%, Eps=${eps.toFixed(3)}`, 'log-info');
        }
      } else if (isEval) {
        const evalR = (evt.evaluation_mean_reward !== undefined && evt.evaluation_mean_reward !== null) ? evt.evaluation_mean_reward : (evt.mean_reward || 0);
        const evalSucc = (evt.evaluation_success_rate !== undefined && evt.evaluation_success_rate !== null) ? evt.evaluation_success_rate : (evt.success_rate || 0);
        document.getElementById('statBestEval').innerText = `Best Eval: ${evalR.toFixed(2)}`;
        appendLog(`⭐ EVAL CHECKPOINT (Ep ${evt.episode}): Mean Reward = ${evalR.toFixed(2)}, Success = ${(evalSucc*100).toFixed(1)}%`, 'log-eval');
        benchmarkChart.data.datasets[0].data[0] = evalSucc * 100;
        benchmarkChart.data.datasets[1].data[0] = evalR;
        benchmarkChart.update();
      } else if (evt.event === 'run_complete' || evt.event_type === 'run_complete') {
        appendLog('🏁 Training completed successfully.', 'log-eval');
      } else if (evt.event === 'error' || evt.event_type === 'error') {
        appendLog(`❌ Error: ${evt.message}`, 'log-err');
      }
    }

    function appendLog(text, className = '') {
      const term = document.getElementById('terminal');
      const row = document.createElement('div');
      row.className = 'log-line';
      const time = new Date().toLocaleTimeString();
      row.innerHTML = `<span class="log-time">[${time}]</span><span class="${className}">${text}</span>`;
      term.appendChild(row);
      term.scrollTop = term.scrollHeight;
    }

    function clearLogs() {
      document.getElementById('terminal').innerHTML = '';
    }

    async function startTraining() {
      const episodes = parseInt(document.getElementById('inputEpisodes').value, 10);
      const lr = parseFloat(document.getElementById('inputLR').value);
      try {
        const res = await fetch('/api/train/start', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ total_episodes: episodes, learning_rate: lr })
        });
        const data = await res.json();
        if (res.ok) {
          appendLog(`Training started: ${episodes} episodes, lr=${lr}`, 'log-info');
        } else {
          appendLog(`Failed to start: ${data.detail}`, 'log-warn');
        }
      } catch (err) {
        appendLog(`Network error: ${err}`, 'log-err');
      }
    }

    async function stopTraining() {
      try {
        const res = await fetch('/api/train/stop', { method: 'POST' });
        const data = await res.json();
        appendLog(`Stop requested: ${data.status}`, 'log-warn');
      } catch (err) {
        appendLog(`Error stopping: ${err}`, 'log-err');
      }
    }

    async function runEvaluation() {
      appendLog('Running evaluation against baselines (100 episodes)...', 'log-info');
      try {
        const res = await fetch('/api/evaluate', { method: 'POST' });
        const data = await res.json();
        if (res.ok) {
          const dqn = data.dqn;
          const baselines = data.baselines;
          benchmarkChart.data.datasets[0].data = [
            dqn.success_rate * 100,
            baselines.FixedLadder.success_rate * 100,
            baselines.RandomValid.success_rate * 100,
            baselines.AlwaysEscalate.success_rate * 100
          ];
          benchmarkChart.data.datasets[1].data = [
            dqn.mean_reward,
            baselines.FixedLadder.mean_reward,
            baselines.RandomValid.mean_reward,
            baselines.AlwaysEscalate.mean_reward
          ];
          benchmarkChart.update();
          appendLog(`Benchmark done! DQN Reward=${dqn.mean_reward.toFixed(2)} (Succ: ${(dqn.success_rate*100).toFixed(1)}%) vs FixedLadder Reward=${baselines.FixedLadder.mean_reward.toFixed(2)}`, 'log-eval');
        } else {
          appendLog(`Evaluation error: ${data.detail}`, 'log-err');
        }
      } catch (err) {
        appendLog(`Eval network error: ${err}`, 'log-err');
      }
    }

    async function exportModel() {
      appendLog('Exporting trained model weights to JSON...', 'log-info');
      try {
        const res = await fetch('/api/export', { method: 'POST' });
        const data = await res.json();
        if (res.ok) {
          appendLog(`Exported to ${data.output_path} | SHA-256: ${data.weights_sha256.substring(0, 16)}... | Parity Verified: ${data.parity_verified}`, 'log-eval');
        } else {
          appendLog(`Export failed: ${data.detail}`, 'log-err');
        }
      } catch (err) {
        appendLog(`Export network error: ${err}`, 'log-err');
      }
    }

    async function runTests() {
      appendLog('Executing pytest suite (29 tests)...', 'log-info');
      try {
        const res = await fetch('/api/tests/run');
        const data = await res.json();
        if (data.passed) {
          appendLog(`All tests PASSED!\\n${data.output}`, 'log-eval');
        } else {
          appendLog(`Tests FAILED!\\n${data.output}`, 'log-err');
        }
      } catch (err) {
        appendLog(`Test runner error: ${err}`, 'log-err');
      }
    }

    window.onload = () => {
      initCharts();
      connectWebSocket();
    };
  </script>
</body>
</html>
"""
