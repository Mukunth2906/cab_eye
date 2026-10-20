"""
Cab Eye — DQN Dialogue Recovery CLI Entry Point.

Commands:
  test      Run the full 29-test unit test suite
  train     Train Double DQN offline in the simulated environment
  evaluate  Evaluate trained agent vs baselines (FixedLadder, RandomValid, AlwaysEscalate)
  export    Export trained network weights to JSON with Kotlin verification vectors
  serve     Launch the FastAPI training service and real-time dashboard UI
  demo      Run end-to-end quick training, evaluation, export, and parity verification
"""

import argparse
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(
        description="Cab Eye — Double DQN Dialogue Recovery CLI",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    # 1. test
    sub_test = subparsers.add_parser("test", help="Run full test suite via pytest")

    # 2. train
    sub_train = subparsers.add_parser("train", help="Train Double DQN agent")
    sub_train.add_argument("--config", type=str, default=None, help="Path to config YAML")
    sub_train.add_argument("--episodes", type=int, default=1000, help="Total training episodes")
    sub_train.add_argument("--seed", type=int, default=42, help="Random seed")
    sub_train.add_argument("--lr", type=float, default=None, help="Learning rate")
    sub_train.add_argument("--eval-interval", type=int, default=200, help="Episodes between evaluations")

    # 3. evaluate
    sub_eval = subparsers.add_parser("evaluate", help="Evaluate agent against baselines")
    sub_eval.add_argument("--checkpoint", type=str, default="artifacts/checkpoints/checkpoint_latest.pt", help="Checkpoint path")
    sub_eval.add_argument("--episodes", type=int, default=200, help="Evaluation episodes")
    sub_eval.add_argument("--seed", type=int, default=99, help="Evaluation seed")

    # 4. export
    sub_export = subparsers.add_parser("export", help="Export PyTorch model weights to JSON for Kotlin inference")
    sub_export.add_argument("--checkpoint", type=str, default="artifacts/checkpoints/checkpoint_latest.pt", help="Checkpoint path")
    sub_export.add_argument("--output", type=str, default="artifacts/recovery_policy_v1.json", help="Output JSON path")

    # 5. serve
    sub_serve = subparsers.add_parser("serve", help="Launch FastAPI web dashboard and training service")
    sub_serve.add_argument("--host", type=str, default="127.0.0.1", help="Host interface")
    sub_serve.add_argument("--port", type=int, default=8000, help="Port")

    # 6. eda
    sub_eda = subparsers.add_parser("eda", help="Run Exploratory Data Analysis (EDA) on training & test metrics")
    sub_eda.add_argument("--metrics", type=str, default="artifacts/metrics", help="Path to metrics file or dir")
    sub_eda.add_argument("--output-dir", type=str, default="reports/eda", help="Output directory for EDA plots & reports")

    # 7. demo
    subparsers.add_parser("demo", help="Run end-to-end training (500 eps), evaluation, export, and parity verification")

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        sys.exit(0)

    if args.command == "test":
        import pytest
        sys.exit(pytest.main(["dialogue_recovery/tests/test_all.py", "-v"]))

    elif args.command == "train":
        from dialogue_recovery.config import default_config, load_config
        from dialogue_recovery.train import train

        cfg = load_config(args.config) if args.config else default_config()
        if args.episodes:
            cfg.num_episodes = args.episodes
        if args.eval_interval:
            cfg.eval_frequency = args.eval_interval
        if args.lr:
            cfg.learning_rate = args.lr

        print(f"Starting DQN training: {cfg.num_episodes} episodes, seed={args.seed}, lr={cfg.learning_rate}")
        result = train(cfg, seed=args.seed)
        print("\nTraining complete!")
        print(f"Run ID: {result['run_id']}")
        print(f"Checkpoint Dir: {result['checkpoint_dir']}")
        print(f"Exported Model: {result['export_path']}")

    elif args.command == "evaluate":
        from dialogue_recovery.config import default_config
        from dialogue_recovery.dqn_model import DQNAgent
        from dialogue_recovery.evaluate import compare_policies

        cp = Path(args.checkpoint)
        if not cp.exists() or "*" in str(cp):
            candidates = sorted(
                list(Path("artifacts").glob("**/final.pt")) + list(Path("artifacts").glob("**/best.pt")),
                key=lambda p: p.stat().st_mtime,
                reverse=True,
            )
            if candidates:
                cp = candidates[0]

        if not cp.exists():
            print(f"Error: Checkpoint '{cp}' does not exist. Train a model first using 'python main.py train'.")
            sys.exit(1)

        print(f"Loading checkpoint from {cp}...")
        cfg = default_config()
        agent = DQNAgent(cfg)
        agent.load_checkpoint(cp)

        print(f"\n--- Comparing Policies ({args.episodes} episodes, seed={args.seed}) ---")
        comparison = compare_policies(agent, cfg, num_episodes=args.episodes, seed=args.seed)
        print(f"{'Policy':18s} | {'Return':>8s} | {'Success':>8s} | {'WrongDest':>10s} | {'Turns':>6s} | {'Escape':>7s}")
        print("-" * 70)
        for name, r in comparison.items():
            print(f"{name:18s} | {r.mean_return:8.2f} | {r.success_rate*100:7.1f}% | {r.incorrect_destination_rate*100:9.1f}% | {r.mean_turns:6.2f} | {r.escape_rate*100:6.1f}%")

    elif args.command == "export":
        from dialogue_recovery.export_model import export_dqn_to_json, verify_export_parity

        cp = Path(args.checkpoint)
        if not cp.exists() or "*" in str(cp):
            candidates = sorted(
                list(Path("artifacts").glob("**/final.pt")) + list(Path("artifacts").glob("**/best.pt")),
                key=lambda p: p.stat().st_mtime,
                reverse=True,
            )
            if candidates:
                cp = candidates[0]

        if not cp.exists():
            print(f"Error: Checkpoint '{cp}' not found. Run training first.")
            sys.exit(1)

        print(f"Exporting {cp} to {args.output}...")
        export_dqn_to_json(cp, args.output)
        print("Verifying parity with Kotlin forward pass emulator...")
        verify_export_parity(args.output)
        print("Export & verification SUCCESSFUL!")

    elif args.command == "serve":
        import uvicorn
        print(f"Starting Cab Eye DQN Dashboard & Training Service on http://{args.host}:{args.port}")
        uvicorn.run("dialogue_recovery.server:app", host=args.host, port=args.port, reload=False)

    elif args.command == "eda":
        from dialogue_recovery.eda import generate_eda_report
        print(f"Executing Exploratory Data Analysis (EDA) on {args.metrics}...")
        res = generate_eda_report(args.metrics, args.output_dir)
        stats = res["stats"]
        acc_m = stats.get("accuracy_metrics", {})
        loss_m = stats.get("loss_metrics", {})
        err_m = stats.get("error_metrics", {})

        print("\n══════════════════════════════════════════════════════════════")
        print("         CAB EYE DQN — EXPLORATORY DATA ANALYSIS (EDA)")
        print("══════════════════════════════════════════════════════════════")
        print(f"Episodes Analyzed:      {stats.get('total_training_episodes', 0)}")
        print(f"Accuracy (Test/Eval):   {acc_m.get('initial_accuracy', 0)*100:5.1f}% -> {acc_m.get('final_accuracy', 0)*100:5.1f}% (Peak: {acc_m.get('peak_accuracy', 0)*100:5.1f}%)")
        print(f"TD Huber Loss:          {loss_m.get('initial_loss', 0):8.4f} -> {loss_m.get('final_loss', 0):8.4f} (Min: {loss_m.get('min_loss', 0):.4f})")
        print(f"Incorrect Dest Error:   {err_m.get('final_incorrect_destination_error', 0)*100:5.2f}% (Safety Boundary: {'PASS' if err_m.get('safety_boundary_maintained') else 'FAIL'})")
        print(f"Abandonment Error:      {err_m.get('final_abandonment_error', 0)*100:5.2f}%")
        print(f"Total Error Rate:       {err_m.get('final_total_error', 0)*100:5.2f}%")
        print("──────────────────────────────────────────────────────────────")
        print("Generated Visual Artifacts & Reports:")
        for name, p in res["plots"].items():
            print(f"  • Plot [{name:20s}]: {p}")
        print(f"  • Summary JSON:               {res['summary_json']}")
        print(f"  • Test Metrics CSV:           {res['test_metrics_csv']}")
        print(f"  • HTML Interactive Report:    {res['html_report']}")
        print("══════════════════════════════════════════════════════════════")

    elif args.command == "demo":
        from dialogue_recovery.config import default_config
        from dialogue_recovery.evaluate import compare_policies
        from dialogue_recovery.export_model import export_dqn_to_json, verify_export_parity
        from dialogue_recovery.train import train

        print("=== Step 1: Training Double DQN Agent (500 episodes) ===")
        cfg = default_config()
        cfg.num_episodes = 500
        cfg.eval_frequency = 250
        cfg.eval_episodes = 100
        result = train(cfg, seed=42)

        print("\n=== Step 2: Evaluation Results vs Baselines ===")
        comparison = result.get("comparison", {})
        print(f"{'Policy':18s} | {'Return':>8s} | {'Success':>8s} | {'WrongDest':>10s} | {'Turns':>6s} | {'Escape':>7s}")
        print("-" * 70)
        for name, r in comparison.items():
            if isinstance(r, dict):
                print(f"{name:18s} | {r.get('mean_return', 0):8.2f} | {r.get('success_rate', 0)*100:7.1f}% | {r.get('incorrect_destination_rate', 0)*100:9.1f}% | {r.get('mean_turns', 0):6.2f} | {r.get('escape_rate', 0)*100:6.1f}%")
            else:
                print(f"{name:18s} | {r.mean_return:8.2f} | {r.success_rate*100:7.1f}% | {r.incorrect_destination_rate*100:9.1f}% | {r.mean_turns:6.2f} | {r.escape_rate*100:6.1f}%")

        print("\n=== Step 3: Verifying Kotlin Export Parity ===")
        cp_path = Path(result["checkpoint_dir"]) / "final.pt"
        if not cp_path.exists():
            cp_path = Path(result["checkpoint_dir"]) / "best.pt"
        verified_export_path = "artifacts/recovery_policy_v1.json"
        export_dqn_to_json(cp_path, verified_export_path)
        verify_export_parity(verified_export_path)
        print(f"Verified Kotlin JSON weights written to {verified_export_path}")
        print("\nDemo completed successfully!")


if __name__ == "__main__":
    main()
