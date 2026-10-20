Cab Eye --- DQN Dialogue Recovery
Full Implementation Specification for Antigravity
Project: Cab Eye --- voice-first cab booking for blind and visually
impaired riders
Feature: Deep Q-Network (DQN) chooses the next recovery action when
speech/destination resolution fails
Primary integration: Existing Android/Kotlin dialogue recovery,
especially RecoveryLadder.kt
Training: Python + PyTorch, offline and reproducible
Visualization: Live training dashboard with charts and metrics
streamed from the actual training process
Default behavior: Existing deterministic recovery ladder until DQN
is trained, evaluated, and explicitly enabled
This document is the implementation contract. Antigravity must inspect
the current repository before changing code. Do not fabricate metrics,
model files, test results, data, or successful integrations.

1. Objective and research question
The current Cab Eye voice flow can fail to understand a destination
because of low speech confidence, ambiguous place matches, noise, or
repeated recognition failures. A fixed recovery ladder uses predefined
recovery steps. This feature will train a DQN agent to choose the next
valid recovery action from the current dialogue state.

Research question: Can a learned recovery policy reduce the number
of dialogue turns required to confirm a destination, compared with the
existing RecoveryLadder, without reducing destination-confirmation
reliability?

DQN only recommends a recovery action. It must not replace speech
recognition, intent parsing, place matching, explicit confirmation,
ride-state management, SOS behavior, or other safety-critical logic.
Success metrics
Evaluate DQN and the existing baseline on identical held-out
scenarios: - Correct destination-confirmation rate. - Incorrect
destination acceptance rate --- must be zero in the test suite. -
Mean and median recovery turns to confirmation. - Recovery failure and
unresolved-dialogue rates. - Metrics split by scenario: low confidence,
ambiguity, no candidates, saved place available, repeated failures, and
noisy environment if measured. - Action distribution and action-mask
rejection count. - Model inference latency (p50/p95), fallback count,
model load errors, and runtime errors. - Model size and memory footprint
on the target Android device. - Training reward and evaluation reward,
clearly distinguished from real-user outcomes.

Do not claim DQN improves Cab Eye until the measured comparison supports
that conclusion.
2. Mandatory repository inspection
The known project includes an Android/Kotlin Jetpack Compose rider app,
on-device speech input, intent parsing, confidence-gated place matching,
saved/remembered places, TTS/audio accessibility, rider state
management, and a Spring Boot backend. RecoveryLadder.kt is the
expected starting point, but exact paths and interfaces must be
confirmed in the current checkout.

Antigravity must first: 1. Inspect the current branch, working tree,
Gradle configuration, Android package structure, and tests. 2. Find
RecoveryLadder.kt and all callers. 3. Trace the path from speech
recognition result through intent/place resolution to recovery action
execution. 4. Identify real confidence values, candidate scores,
saved-place API, prompt/TTS functions, and dialogue state transitions.
5. Identify whether an actual ambient-noise measurement exists. Do not
assume it does. 6. Report the current flow and proposed file changes
before editing. 7. Preserve unrelated behavior and do not introduce
duplicate state/action types unnecessarily.
3. Target architecture
Android rider app
  Rider speaks
       |
  Existing on-device speech recognition
       |
  Existing intent parser + place matcher
       |
       +-- confident/valid result --> existing explicit confirmation flow
       |
       +-- unresolved result
                |
         Build dialogue state
                |
         DialogueRecoveryPolicy
           /             \
    DQN inference       baseline RecoveryLadder
           \             /
          validate allowed action
                |
         existing dialogue controller
                |
         speak prompt / collect response
                |
         next state + terminal outcome
                |
         privacy-minimized event (optional/consented)

Offline ML workspace (Python)
  dataset or documented synthetic environment
       |
  DQN training --> checkpoints + metrics
       |
  evaluation against fixed baseline
       |
  export/version frozen model
       |
  Android inference integration

Training dashboard
  actual training process --> metrics event stream --> live UI
                           --> charts + status + logs
Responsibilities
Android app
- Create state from actual app signals.
- Invoke policy only when recovery is required.
- Execute the selected action through existing
  dialogue/TTS/accessibility components.
- Enforce valid-action masks and explicit confirmation.
- Keep the current ladder as the fallback and baseline.
- Never explore randomly or train online with real riders.
Python training workspace
- Provide a documented synthetic environment and a data adapter for
  consented, minimized logs if available.
- Train DQN with reproducible configuration and seeds.
- Evaluate against the deterministic baseline.
- Save actual training/evaluation metrics, checkpoints, and model
  metadata.
- Stream metrics from the real training process to the dashboard.
Dashboard
- Display metrics emitted by the running trainer, not hardcoded sample
  values.
- Update automatically while training is running.
- Show clear states: idle, preparing, training, evaluating, completed,
  failed, stopped.
- Distinguish synthetic/simulated metrics from real-world data.
- Show an empty state before training starts; never show fabricated
  "live" metrics.
Backend
The initial training dashboard may run locally and need not change the
production backend. Prefer a local FastAPI service for training control
and metric streaming if the dashboard requires a service. Do not expose
training-control endpoints publicly without authentication and explicit
protection.
4. DQN and neural-network concepts
4.1 Markov Decision Process
Model dialogue recovery as a finite Markov Decision Process:
R, `\gamma`{=tex}) \]

-   (`\mathcal{S}`{=tex}): compact dialogue states.
-   (`\mathcal{A}`{=tex}): discrete recovery actions.
-   (P(s' `\mid`{=tex}s,a)): environment's next-state distribution after
    action (a).
-   (R(s,a,s')): reward from the resulting outcome.
-   (`\gamma`{=tex}`\in`{=tex}\[0,1)): discount factor that balances
    immediate and future reward.

The assumption is that the state includes enough history (such as
failure count, last action, and last outcome) to make the next-step
decision useful. If relevant information is missing, document the
limitation rather than assuming the state is perfectly Markovian.

## 4.2 State vector

Use only signals that the existing app actually exposes. A candidate
initial state is:

\[ s_t = \[f_t, c_t, p_t, n_t, q_t, m_t, a\_{t-1}, e\_{t-1}, d_t\] \]

where: - (f_t): clipped/normalized consecutive failure count. - (c_t):
speech confidence, normalized to (\[0,1\]), with explicit missing-value
handling. - (p_t): encoded place type. - (n_t): candidate count,
clipped/normalized. - (q_t): best candidate score, if available. -
(m_t): relevant saved-place availability. - (a\_{t-1}): previous action
encoding. - (e\_{t-1}): previous action failed indicator. - (d_t):
dialogue stage encoding.

Ambient noise may be included only if a reliable measurement is
available. If not, omit it in v1 and document that choice. Version the
state schema and keep feature order identical between training and
Android inference.

  ---------------------------------------------------------------------
  Feature                            Encoding
  ---------------------------------- ----------------------------------
  Failure count                      Clip to defined maximum, then
                                     normalize

  Confidence/candidate score         Float in \[0,1\], plus explicit
                                     unknown handling

  Place type/dialogue stage/previous One-hot or stable categorical
  action                             encoding

  Candidate count                    Clipped numeric feature

  Saved place/previous failure       Boolean 0/1
  ---------------------------------------------------------------------

Never place raw audio, full transcripts, personal names, exact
addresses, coordinates, account identifiers, or other identifying
information into the default training state.

## 4.3 Action set and action masking

Use stable action IDs mapped to real app behaviors:

  -----------------------------------------------------------------------
  Action                  Meaning                 Validity rule
  ----------------------- ----------------------- -----------------------
  `REASK`                 Ask the rider to        Available while
                          repeat/say the          dialogue can continue
                          destination more        
                          clearly                 

  `OFFER_TOP_CANDIDATE`   Speak the best          Candidate exists and
                          candidate and ask for   passes existing
                          confirmation            confidence/ambiguity
                                                  gate

  `YES_NO_CONFIRM`        Ask yes/no about a      Candidate exists;
                          specific candidate      silence is never yes

  `OFFER_SAVED_PLACE`     Offer a relevant saved  Relevant saved place
                          destination             exists; explicit
                                                  confirmation required

  `FALLBACK_HELP`         Existing deterministic  Always available
                          simpler prompt/fallback 
  -----------------------------------------------------------------------

Do not add an action that the application cannot actually execute. If an
action is unsupported, omit it or implement the real behavior before
training.

Let (A\_{`\mathrm{valid}`{=tex}}(s)) be the actions whose preconditions
are true. DQN must choose only from that set:

\[ a_t = `\arg`{=tex}`\max`{=tex}*{a
`\in`{=tex}A*{`\mathrm{valid}`{=tex}}(s_t)} Q\_`\theta`{=tex}(s_t,a) \]

In implementation, invalid actions should be masked before `argmax` (for
example, assigning invalid Q-values negative infinity), and all outputs
must be validated. On empty mask, invalid output, exception, timeout, or
model incompatibility, call the deterministic fallback.

## 4.4 Neural network

Use a small feed-forward multilayer perceptron (MLP) as the initial
Q-network. This is a small, discrete state/action problem; do not start
with a large transformer.

Example architecture, to be validated experimentally: - Input:
normalized state vector of dimension `state_dim`. - Dense layer 1: 64
neurons, ReLU. - Dense layer 2: 64 neurons, ReLU. - Output:
`action_count` linear units; each output estimates a Q-value.

\[ h_1 = `\operatorname{ReLU}`{=tex}(W_1s+b_1) \] \[ h_2 =
`\operatorname{ReLU}`{=tex}(W_2h_1+b_2) \] \[
Q\_`\theta`{=tex}(s,`\cdot`{=tex})=W_3h_2+b_3 \]

Concepts: - **Weights and biases:** trainable parameters that map state
features to Q-value estimates. - **ReLU:**
(`\operatorname{ReLU}`{=tex}(x)=`\max`{=tex}(0,x)), providing a
nonlinear activation. - **Output layer:** one estimated value per
recovery action; these are not probabilities. - **Online network:**
updated by gradient descent. - **Target network:** delayed copy used to
stabilize temporal-difference targets. - **Replay buffer:** stores past
transitions and samples mini-batches to reduce correlation between
consecutive experiences. - **Epsilon-greedy exploration:** used only in
offline training; never on live riders. - **Inference:** deployed model
chooses an action without weight updates.

The hidden-layer size is a starting hyperparameter, not a proven
optimum. Keep architecture configurable.

## 4.5 Bellman optimality and Q-learning

The optimal action-value function obeys the Bellman equation:

\[
Q\^\*(s,a)=`\mathbb{E}`{=tex}`\left[r+\gamma \max_{a'} Q^*(s',a') \mid s,a\right]`{=tex}\]

DQN approximates (Q\^\*) with a neural network (Q\_`\theta`{=tex}(s,a)).
For a transition ((s,a,r,s',done)), use a target network
(Q\_{`\theta`{=tex}\^-}):

\[ y =

```{=tex}
\begin{cases}
r, & done = 1\\
r+\gamma \max_{a'\in A_{\mathrm{valid}}(s')} Q_{\theta^-}(s',a'), & done = 0
\end{cases}
```
Use action masking for the next state too. For terminal transitions, do
not bootstrap from (s').

A common DQN loss is the mean squared temporal-difference error:
`\left`{=tex}(y_i-Q*`\theta`{=tex}(s_i,a_i)`\right`{=tex})\^2 \]

Huber loss is also acceptable and can be more robust to large TD errors:

\[ L\_`\delta`{=tex}(e)=

```{=tex}
\begin{cases}
\frac{1}{2}e^2, & |e|\leq\delta\\
\delta(|e|-\frac{1}{2}\delta), & |e|>\delta
\end{cases}
```
where (e=y-Q_\theta{=tex}(s,a)), and (B) is batch size. Choose and
document one loss in configuration.

A gradient-based update is:
\[
`\theta`{=tex}`\leftarrow`{=tex}`\theta`{=tex}-`\alpha`{=tex}`\nabla`{=tex}\_`\theta`{=tex}L(`\theta`{=tex})
\]
where (\alpha{=tex}) is the learning rate. Update the target network
periodically or with a documented soft-update rule.
4.6 Discount factor and exploration
The discounted return is:

\(G_t = `\sum`{=tex}*{k=0}^{T-t-1}`\gamma`{=tex}^k r*{t+k+1}\)

A larger (\gamma{=tex}) gives more weight to future outcomes. For this
short dialogue task, test a reasonable range rather than assuming one
value is best.

Example epsilon schedule:
`\max`{=tex}(`\epsilon`{=tex}*{`\min`{=tex}},`\epsilon`{=tex}\_0
`\cdot`{=tex}`\lambda`{=tex}\^t) \]

where (`\epsilon`{=tex}*0) is the initial exploration rate,
(`\epsilon`{=tex}*{`\min`{=tex}}) the floor, and (`\lambda`{=tex}) a
decay factor. Exploration is strictly offline. Evaluation and Android
inference use greedy selection over valid actions.

------------------------------------------------------------------------

# 5. Reward design and episode boundaries

Suggested starting rewards for experiments only:

  ---------------------------------------------------------------------
  Outcome                                                Initial reward
  ----------------------------- ---------------------------------------
  Correct destination                                               +10
  explicitly confirmed          

  Each additional recovery turn                                      -1

  Another unresolved attempt                                         -3

  Rider abandons/unresolved                                          -5
  terminal outcome, only if     
  reliably observable           

  Incorrect destination           -10 in offline evaluation, and a hard
  accepted                                               safety failure
  ---------------------------------------------------------------------

The incorrect-destination penalty is not a substitute for a safety gate.
Booking must remain impossible until the existing explicit confirmation
succeeds.

An episode starts when the app enters recovery after an unresolved
speech/intent/place result. It ends on explicit destination
confirmation, rider cancellation, retry limit followed by deterministic
fallback, or a documented terminal failure. Do not extend the episode
into ride execution.

Document reward weights, terminal conditions, and sensitivity
experiments. Do not tune only to make the dashboard look good.

------------------------------------------------------------------------

# 6. Training data and environment

## 6.1 Data sources

Support: 1. **Synthetic environment:** first development option when
real data is unavailable. 2. **Consent-based, privacy-minimized logs:**
optional later, only with proper consent and applicable ethics approval.

The synthetic environment should vary: - Speech confidence and
unresolved failures. - Candidate count and ambiguity. - Whether a
relevant saved place exists. - Outcomes after each action. - Repeated
failures and episode termination. - Scenario types and random seeds.

Document all simulator assumptions and probabilities. Synthetic results
must be labeled `SIMULATED`; they must never be displayed as real rider
results.

## 6.2 Data splitting

Where a dataset is used, separate training, validation, and test
scenarios. Prevent leakage between the training and held-out test sets.
For synthetic scenarios, use distinct random seeds and scenario
configurations for final evaluation. Report the split and scenario
counts.

## 6.3 Reproducibility

Use a config file containing: - Seed(s), episode count, max steps per
episode. - Learning rate, gamma, batch size, replay-buffer capacity. -
Initial/minimum epsilon and decay. - Target-network update frequency or
tau. - Hidden-layer dimensions and loss type. - Evaluation frequency and
number of evaluation episodes. - Checkpoint and metrics output paths. -
Dataset/environment version.

Record the resolved configuration with each run.

------------------------------------------------------------------------

# 7. Live training dashboard --- required behavior

Build a local dashboard so that **graphs and metrics update from the
actual running training job**. The dashboard must not generate its own
pretend training curve or hardcode successful test results.

## 7.1 Suggested stack

-   Training: Python + PyTorch.
-   Environment: plain Python or Gymnasium.
-   Training API: FastAPI, if a service is needed.
-   Live updates: WebSocket or Server-Sent Events from the actual
    training process.
-   Dashboard UI: use the project's preferred existing UI stack if
    available; otherwise a small React + TypeScript + Vite page is
    acceptable. Do not add a frontend framework to the Android app.
-   Charts: Recharts or an existing compatible chart library.
-   Metrics history: append-only JSONL or CSV per run; optional SQLite
    for run metadata.
-   Local run controls: start, stop, and evaluate. Restrict these
    endpoints to localhost or protect them with authentication; do not
    expose arbitrary process execution publicly.

## 7.2 Live metric event contract

Emit an event after every episode, plus periodic minibatch/step updates
at a configurable interval. Suggested JSON schema:

``` json
{
  "run_id": "generated-run-id",
  "timestamp": "ISO-8601 timestamp",
  "event_type": "episode_metrics",
  "phase": "training",
  "episode": 42,
  "environment_steps": 735,
  "episode_reward": 4.0,
  "rolling_mean_reward": 2.7,
  "evaluation_mean_reward": null,
  "loss": 0.183,
  "epsilon": 0.42,
  "success_rate": 0.71,
  "incorrect_destination_rate": 0.0,
  "mean_turns": 2.8,
  "fallback_rate": null,
  "elapsed_seconds": 18.4
}
```

The values above are **schema examples only, not actual results**. The
implementation must populate each field from the running process. Use
`null` for metrics that are not measured yet; never invent a value.
Define units, rolling-window sizes, and whether a metric comes from
training episodes or a separate evaluation run.

The dashboard should tolerate absent optional fields and reject
malformed/out-of-order events safely.

## 7.3 Required dashboard panels

1.  **Run status:**
    idle/preparing/training/evaluating/completed/failed/stopped.
2.  **Live counters:** episode, environment steps, elapsed time, run ID,
    model version.
3.  **Episode reward chart:** raw episode reward plus rolling mean.
4.  **Evaluation reward chart:** evaluated policy performance at
    evaluation checkpoints; separate from training reward.
5.  **Training loss chart:** DQN TD loss, with axis and sampling
    interval labeled.
6.  **Exploration chart:** epsilon over episodes/steps.
7.  **Task metrics:** confirmation success rate, incorrect destination
    rate, mean/median turns, unresolved rate.
8.  **Action distribution:** counts/percentages for REASK,
    TOP_CANDIDATE, YES_NO_CONFIRM, SAVED_PLACE, FALLBACK_HELP.
9.  **Baseline comparison:** baseline and DQN metrics evaluated on the
    same test scenarios.
10. **System information:** model size, inference latency if measured,
    current config, seed, environment version.
11. **Training log panel:** timestamped events, warnings, checkpoint
    saves, exceptions.
12. **Test results panel:** actual executed test cases, pass/fail,
    duration, command, timestamp, and failure summary.

## 7.4 Live update requirements

-   Stream from the actual trainer, not browser-generated random values.
-   Update charts without requiring a page refresh.
-   Show last update time and connection status.
-   Reconnect safely if the stream disconnects; backfill from persisted
    metrics history.
-   Preserve history when training completes or the page reloads.
-   Handle stop, crash, and restart without mixing metrics from
    different runs.
-   Mark metrics as training, validation, or test.
-   Display a prominent `SIMULATED ENVIRONMENT` label when the trainer
    uses synthetic scenarios.
-   Do not call a test result "passed" until the command actually ran
    and exited successfully.
-   If a chart has no data, show "Waiting for training data" rather than
    a fake curve.

## 7.5 Real-time evaluation

At a configurable interval: 1. Pause only at a safe episode/checkpoint
boundary. 2. Run a separate greedy evaluation policy with exploration
disabled. 3. Use a fixed validation scenario set and fixed seeds. 4.
Emit evaluation metrics as a separate event. 5. Resume training. 6. Keep
final held-out test data untouched until final evaluation.

Evaluation must not accidentally update model weights or replay buffer
unless the training design explicitly documents that behavior.

------------------------------------------------------------------------

# 8. Test reporting and evidence

The dashboard and Markdown report must show **actual executed results**,
not expected or illustrative results.

## 8.1 Test record format

``` json
{
  "run_id": "generated-run-id",
  "suite": "android-unit-tests",
  "command": "./gradlew testDebugUnitTest",
  "started_at": "ISO-8601 timestamp",
  "finished_at": "ISO-8601 timestamp",
  "exit_code": 0,
  "status": "PASSED",
  "passed": 0,
  "failed": 0,
  "skipped": 0,
  "summary": "Parsed from the actual test output"
}
```

All counts and summaries must be extracted from actual command output or
test reports. The values shown here are schema examples, not test
results. If parsing fails, show `UNKNOWN` and preserve the raw log; do
not assume a pass.

## 8.2 Required test suites

-   Python unit tests: state encoding, action masks, rewards, terminal
    conditions, replay buffer, target calculation, metrics event schema.
-   DQN tests: output shape, finite Q-values, target calculation,
    reproducibility with fixed seeds, checkpoint save/load,
    invalid-action masking.
-   Environment tests: each scenario terminates, allowed actions are
    valid, synthetic success/failure outcomes match configured
    probabilities.
-   Dashboard tests: event parsing, live update, disconnect/reconnect,
    persisted history, run separation, empty state, failed trainer.
-   Android tests: baseline regression, state-to-feature encoding,
    action mapping, missing model, invalid output, inference timeout,
    fallback, explicit confirmation gate.
-   Integration tests: speech failure enters recovery; confident
    recognition follows existing path; candidate/saved-place prompts
    require confirmation; SOS remains unaffected.
-   Build tests: Android build/tests and Python tests. Backend tests
    only if backend code was changed.

## 8.3 Test evidence and artifacts

For each run, save: - Command and exit code. - Timestamp, duration,
commit/revision if available. - Parsed test summary. - Raw log path. -
Machine-readable metrics file. - Graph data/CSV and evaluation report. -
Model checkpoint path and config hash, when applicable.

Generate a report such as `reports/<run_id>/REPORT.md`, plus
`metrics.jsonl`, `test-results.json`, and chart image/HTML exports if
supported. Never fabricate a test count, reward curve, success rate, or
comparison table.

------------------------------------------------------------------------

# 9. Android inference integration

## 9.1 Policy contract

Adapt this conceptual interface to existing types; do not duplicate
models that already exist.

``` kotlin
data class DialogueRecoveryState(
    val failureCount: Int,
    val speechConfidence: Float?,
    val placeType: PlaceType,
    val candidateCount: Int,
    val topCandidateConfidence: Float?,
    val savedPlaceAvailable: Boolean,
    val lastAction: RecoveryAction?,
    val lastActionFailed: Boolean
)

enum class RecoveryAction {
    REASK,
    OFFER_TOP_CANDIDATE,
    YES_NO_CONFIRM,
    OFFER_SAVED_PLACE,
    FALLBACK_HELP
}

interface DialogueRecoveryPolicy {
    fun chooseAction(
        state: DialogueRecoveryState,
        allowedActions: Set<RecoveryAction>
    ): RecoveryAction
}
```

## 9.2 Integration sequence

1.  Put the current `RecoveryLadder` behind the policy contract without
    changing behavior.
2.  Add baseline regression tests.
3.  Train and evaluate offline before Android model integration.
4.  Choose the inference runtime only after checking current
    Android/Gradle constraints.
5.  Load the frozen model once; validate metadata, dimensions, and
    state/action schema.
6.  Normalize features exactly as in training.
7.  Apply action masking and validate the selected action.
8.  Use a strict latency budget; never block UI/audio.
9.  Execute through the existing dialogue controller.
10. If state changed, discard stale action and fall back/re-evaluate.
11. Add a feature flag with `baseline` as default. DQN must be
    explicitly enabled for controlled testing.
12. Keep the app usable if the model asset is absent or incompatible.

Do not add a large inference runtime without verifying APK size, ABI
support, licensing, maintenance, and device performance. Do not silently
replace DQN with hand-written rules and claim the DQN model is running.

------------------------------------------------------------------------

# 10. Privacy, safety, and accessibility requirements

1.  Explicit destination confirmation is mandatory.
2.  Silence, timeout, or unclear speech never means "yes."
3.  Existing candidate matching and confidence gates remain
    authoritative.
4.  Saved places must be clearly spoken and explicitly confirmed.
5.  DQN must not intercept, delay, or change SOS/emergency flows.
6.  DQN must not jump dialogue or ride states.
7.  Preserve bounded retries and deterministic fallback.
8.  Model missing, exception, timeout, non-finite output, invalid
    action, or schema mismatch must fall back safely.
9.  No online exploration or automatic production retraining.
10. Preserve speech prompts, earcons, vibration, TalkBack semantics, and
    accessibility behavior.
11. Use privacy-minimized data; obtain consent and follow applicable
    ethics requirements for research data.
12. Never commit audio, private transcripts, exact personal
    destinations, credentials, or raw user logs.
13. Keep local training controls restricted to localhost or
    authenticated.
14. Do not display synthetic results as real-user results.

------------------------------------------------------------------------

# 11. Suggested repository structure

Adapt this to the actual repository and avoid duplicating existing
components.

``` text
android/
  app/src/main/java/com/cabeye/rider/
    dialogue/
      RecoveryLadder.kt
      DialogueRecoveryPolicy.kt
      DialogueRecoveryState.kt
      DqnDialogueRecoveryPolicy.kt
      RecoveryActionValidator.kt
  app/src/test/...

ml/
  dialogue_recovery/
    README.md
    requirements.txt
    config.yaml
    environment.py
    state_schema.py
    actions.py
    reward.py
    replay_buffer.py
    dqn_model.py
    train.py
    evaluate.py
    export_model.py
    metrics.py
    tests/
    data/README.md
    artifacts/README.md

dashboard/
  README.md
  package.json
  src/
    components/
    charts/
    api/
    types/

training_service/
  README.md
  app.py
  schemas.py
  run_manager.py
  tests/

reports/
  README.md

docs/
  DQN-DIALOGUE-RECOVERY-IMPLEMENTATION.md
```

A separate dashboard/service is optional if the repository already has a
suitable local dashboard architecture. Prefer existing tools and minimal
new dependencies.

------------------------------------------------------------------------

# 12. Antigravity phased execution plan

## Phase 0 --- Inspect and report

-   Inspect repository, branch, working tree, current dialogue flow,
    tests, build constraints.
-   Report actual interfaces and proposed file changes.
-   Do not edit until the existing flow is understood.
-   Do not change booking, payment, driver assignment, backend API, or
    unrelated UI.

## Phase 1 --- Baseline policy interface

-   Add/reuse policy contract and adapt the current ladder.
-   Preserve identical behavior.
-   Add regression tests.
-   Run tests/build and report actual output before moving on.

## Phase 2 --- Mathematical DQN core

-   Implement state schema, valid-action masks, MLP Q-network, replay
    buffer, target network, TD loss, epsilon schedule, reward and
    episode termination.
-   Add deterministic seeded tests.
-   Include formulas and definitions in the ML README.
-   Keep training isolated from Android runtime.

## Phase 3 --- Synthetic environment and baseline evaluation

-   Implement documented simulator and scenario types.
-   Train the model offline.
-   Evaluate against baseline on identical held-out scenarios.
-   Clearly label simulated outcomes.
-   Do not integrate model into Android until results and model artifact
    are valid.

## Phase 4 --- Live training metrics and dashboard

-   Add actual trainer-to-dashboard event stream.
-   Persist metrics to JSONL/CSV.
-   Implement live reward/loss/epsilon/evaluation charts, task metrics,
    action distribution, logs, run status, and test-results panel.
-   Test stream disconnect/reconnect and history reload.
-   Empty dashboard must show no data, not fake data.

## Phase 5 --- Frozen-model export and Android integration

-   Verify inference runtime in the actual Android build.
-   Export model and metadata.
-   Implement inference behind the policy interface with masks,
    validation, timeout, and fallback.
-   Keep baseline default and DQN behind a feature flag.
-   Add Android tests for missing/incompatible model and confirmation
    safety.

## Phase 6 --- Full validation and report

-   Run Python unit tests, dashboard tests, Android tests/build, and
    applicable integration tests.
-   Run reproducible training and baseline comparison.
-   Produce report, raw logs, metrics JSONL/CSV, test results JSON,
    chart exports, config and model metadata.
-   Report limitations and next steps.
-   Do not enable DQN by default unless pre-defined acceptance criteria
    are met.

### Rules for Antigravity

-   Make small, reviewable changes.
-   Preserve existing business logic outside recovery-action selection.
-   Never invent APIs, data, metrics, graph points, model artifacts, or
    test results.
-   A graph is "live" only if it receives actual emitted trainer
    metrics.
-   A test is passed only when executed and its result parsed/verified.
-   If a required signal, data source, or inference runtime is missing,
    report the blocker rather than fabricating it.
-   After every phase, report files changed, commands run, exit codes,
    test outcomes, graph/data artifacts, and unresolved issues.

------------------------------------------------------------------------

# 13. Definition of done

-   [ ] Current ladder is preserved as baseline and fallback.
-   [ ] State/action schema and feature normalization are versioned.
-   [ ] DQN network, target network, replay buffer, TD loss, and action
    masking are implemented and tested.
-   [ ] Training is reproducible with saved configuration and seeds.
-   [ ] Synthetic data is labeled clearly; no fabricated real-world
    data.
-   [ ] DQN and baseline are evaluated on identical held-out scenarios.
-   [ ] Dashboard streams real training events and updates charts
    without refresh.
-   [ ] Reward, loss, epsilon, evaluation, task, action-distribution,
    and test-result panels work.
-   [ ] Metrics persist across refresh/reconnect and are separated by
    run ID.
-   [ ] Test results are derived from actual command output.
-   [ ] Model artifact and metadata are versioned and loaded by Android
    inference.
-   [ ] Model failure reliably falls back to the current ladder.
-   [ ] Explicit confirmation, SOS, accessibility, and state-machine
    tests pass.
-   [ ] Android build/tests and Python/dashboard tests have recorded
    actual results.
-   [ ] Report, logs, metrics, charts, and config are saved as run
    artifacts.
-   [ ] DQN is disabled by default until evaluated and explicitly
    enabled.

------------------------------------------------------------------------

# 14. Expected research contribution and limitations

The contribution is an adaptive recovery policy for a voice-first
accessible cab-booking flow, supported by reproducible DQN training,
baseline comparison, and observable training metrics.

Limitations: - A synthetic environment may not represent real speech
failures or blind riders' interaction patterns. - Recognition confidence
may not be calibrated across devices/noise conditions. - DQN may not
outperform a well-designed deterministic ladder, especially with little
data. - Training reward is not equivalent to real-world usability. - A
frozen policy does not adapt after release without controlled retraining
and re-evaluation. - Evaluation with blind and visually impaired
participants requires appropriate consent, applicable ethics review, and
accessibility/O&M expertise.

**First milestone:** inspect the current code, introduce the baseline
policy interface without changing user experience, and add regression
tests. Then implement and test the offline DQN core and live metrics
dashboard. Integrate the frozen model into Android only after the
evaluation artifacts and runtime are verified.

------------------------------------------------------------------------

# 15. Concrete DQN configuration from the uploaded experiment

This section records the specific experiment described in the supplied
notes. It narrows the earlier generic recommendations into a concrete
implementation target. **For this experiment, the 16-input,
64-64-hidden-layer, 5-output Double DQN and the action names below are
the reference design.** Before connecting it to Cab Eye, Antigravity
must verify that these five actions map to real behaviors in the current
source code.

## 15.1 Neural network architecture

``` text
16 state features
      |
      v
Dense(64) + ReLU
      |
      v
Dense(64) + ReLU
      |
      v
Dense(5) linear Q-values
```

Layer dimensions:

  Layer        Input width   Output width Activation
  ---------- ------------- -------------- ------------
  Input                ---             16 None
  Hidden 1              16             64 ReLU
  Hidden 2              64             64 ReLU
  Output                64              5 Linear

Forward pass:

\[ h_1 = `\operatorname{ReLU}`{=tex}(W_1s+b_1) \]

\[ h_2 = `\operatorname{ReLU}`{=tex}(W_2h_1+b_2) \]

\[ Q\_`\theta`{=tex}(s,`\cdot`{=tex})=W_3h_2+b_3 \]

where (W_1`\in`{=tex}`\mathbb{R}`{=tex}\^{64`\times16`{=tex}}),
(b_1`\in`{=tex}`\mathbb{R}`{=tex}\^{64}),
(W_2`\in`{=tex}`\mathbb{R}`{=tex}\^{64`\times64`{=tex}}),
(b_2`\in`{=tex}`\mathbb{R}`{=tex}\^{64}),
(W_3`\in`{=tex}`\mathbb{R}`{=tex}\^{5`\times64`{=tex}}), and
(b_3`\in`{=tex}`\mathbb{R}`{=tex}\^{5}).

\[ `\operatorname{ReLU}`{=tex}(x)=`\max`{=tex}(0,x) \]

The output layer is linear because Q-values are expected returns, not
probabilities; they can be negative or greater than one.

### Parameter count

\[ (16`\times64`{=tex}+64)+(64`\times64`{=tex}+64)+(64`\times5`{=tex}+5)
\(=1088+4160+325=`\boxed{5573\text{ parameters}}`{=tex}\)

At float32, 5,573 parameters occupy approximately 22,292 bytes (about
21.8 KiB, often rounded to 22 KB), excluding JSON formatting, runtime
objects, and metadata. This is small enough that a plain Kotlin
FloatArray forward pass is a reasonable candidate, provided parity
tests prove that Kotlin inference matches the trained Python model.
15.2 Exact 16-feature state proposal
The uploaded experiment defines these features, with values
scaled/encoded into (\(0,1\)) where applicable:
  Index / feature         Meaning                 Encoding notes
  0. Attempt number      Current recovery        Divide by 3 and clip to the
                          attempt                 documented range

  1. Blank transcript    Whether the recognizer  Boolean 0/1
                          returned a blank
                          transcript              

  2. Word count          Number of words         Normalize by a documented
                          recognized              cap, e.g. 5; use the same
                                                  rule in training and Kotlin

  3. Best place-match    Highest                 Normalize only if the source
  score                   candidate/matcher score score is not already in
                                                  \(0,1\)

  4--7. MatchGate failure Four checks that failed Boolean 0/1 in a fixed,
  flags                   in the current          documented order
                          MatchGate             

  8. Candidate exists    Whether a candidate     Boolean 0/1
                          place exists            

  9--13. Previous action  Five one-hot values for Exactly one active value
                          the previous action     when a previous action
                                                  exists; define the
                                                  initial/no-previous-action
                                                  representation explicitly

  14. Saved places exist Whether the rider has   Boolean 0/1; ideally
                          saved places available  distinguish "any saved
                                                  place" from "relevant saved
                                                  place" if the existing API
                                                  supports that distinction
  15. Repeated same      Whether the rider       Boolean 0/1
  words                   repeated the same words 
Important implementation check: The feature list is taken from the
supplied experiment notes. Antigravity must inspect the actual
MatchGate implementation to identify the exact four checks and their
stable order. Do not guess these checks. If the repository does not
expose a reliable feature, either add a tested adapter that derives it
from existing data or revise the schema and retrain. Do not pass
placeholder values as if they were live observations.

The uploaded notes describe all features as scaled to 0--1.
Categorical/one-hot features are already in that range. Define and test
exact clipping and normalization rules for attempt number, word count,
and match score. Do not normalize a score twice. Version the state
schema and use identical feature ordering in Python and Kotlin.
15.3 Action IDs for the concrete experiment
The supplied training experiment uses these five outputs:
       Output index Action ID              Intended         Required guard
                                           behavior         
                  0 `NAME_REASON`          Ask a brief      Must map to an
                                           question to      existing or newly
                                           clarify what the implemented real
                                           rider means or   spoken prompt
                                           why the match    
                                           failed           

                  1 `OFFER_ALTERNATIVES`   Speak a short    Candidates must exist;
                                           list of          do not overwhelm the
                                           plausible        rider with a long list
                                           destination      
                                           alternatives     

                  2 `OFFER_ESCAPES`        Offer safe ways  Must map to actual
                                           to recover from  supported recovery
                                           the dead end,    behavior
                                           such as          
                                           repeating or     
                                           using another    
                                           input path       

                  3 `CONFIRM_TOP_GUESS`    Speak the top    Candidate must pass
                                           candidate and    the existing
                                           request explicit confidence/ambiguity
                                           confirmation     guard

                  4 `OFFER_SAVED_PLACES`   Offer a relevant A relevant saved
                                           saved            destination must
                                           destination      exist; explicit
                                                            confirmation is
                                                            mandatory
These names differ from the earlier generic action labels (REASK,
YES_NO_CONFIRM, etc.). Use one canonical action enum throughout the
trainer, exported model, Kotlin inference, tests, and telemetry. The
concrete experiment's five names above are the reference action IDs
unless source inspection proves a behavior is unsupported. Do not map
two names to unrelated behaviors or silently substitute a heuristic.
Valid-action masking
For state (s), let (A_{\mathrm{valid}{=tex}}(s)) be the action set
whose preconditions are satisfied:
\[
a\^\*=`\arg`{=tex}`\max`{=tex}*{a`\in `{=tex}A*{`\mathrm{valid}`{=tex}}(s)}Q\_`\theta`{=tex}(s,a)
\]
Set invalid action values to (-\infty{=tex}) before argmax, or use
an equivalent mask that cannot accidentally select an invalid action.
For example, OFFER_SAVED_PLACES is invalid if no relevant saved place
is available; CONFIRM_TOP_GUESS is invalid when no eligible candidate
exists. Also mask next-state actions when computing the Double DQN
target.

If no normal action is valid, OFFER_ESCAPES must provide a tested
deterministic recovery route, or the current RecoveryLadder must be
invoked. Model output must never directly book a ride.
15.4 Double DQN training configuration from the uploaded notes
The supplied experiment reports these starting settings:

  Parameter                          Value in supplied experiment
  Network                            16 → 64 ReLU → 64 ReLU → 5 linear
  Algorithm                          Double DQN
  Optimizer                          Adam
  Learning rate                      0.001
  Discount factor (\gamma{=tex})   0.9
  Batch size                         64 transitions
  Target network refresh             Every 500 training steps
  Exploration                        Epsilon-greedy, from 1.0 down to 0.05
  Deployment exploration             (\epsilon=0{=tex}), greedy over valid actions
  Weight initialization              He/Kaiming initialization for ReLU layers
  Loss                               Huber loss
  Training runs reported             Five seeds, 40,000 episodes per seed

These are experiment settings, not proven optimal settings. Store them
in a configuration file and log the resolved config for every run.
Double DQN target
For a transition ((s,a,r,s',done)), the online network selects the next
action and the target network evaluates it:
\[
a\^\**{`\mathrm{next}`{=tex}}=`\arg`{=tex}`\max`{=tex}*{a'`\in `{=tex}A\_{`\mathrm{valid}`{=tex}}(s')}Q\_{`\theta`{=tex}}(s',a')
\]
\[
```{=tex}
\begin{cases}
r, & \text{if } done=1\\
r+\gamma Q_{\theta^-}(s',a^*_{\mathrm{next}}), & \text{if } done=0
\end{cases}
```
\]
Here, (Q_\theta{=tex}) is the online network and
(Q_{\theta{=tex}^-}) is the target network. The target network is
refreshed every 500 training steps in the reported configuration.
Terminal episodes must not bootstrap from the next state. Use the
valid-action mask in the next-state argmax.
Huber loss
For TD error (e=y-Q_\theta{=tex}(s,a)):
\[
```{=tex}
\begin{cases}
\frac{1}{2}e^2, & |e|\leq\delta\\
\delta(|e|-\frac{1}{2}\delta), & |e|>\delta
\end{cases}
```
\]
For a batch of size (B):
L(`\theta`{=tex})=`\frac{1}{B}`{=tex}`\sum`{=tex}*{i=1}\^{B}L*`\delta`{=tex}`\left`{=tex}(y_i-Q\_`\theta`{=tex}(s_i,a_i)`\right`{=tex})
Update online weights with Adam using gradients of this loss. The
uploaded notes mention Huber loss but do not specify (\delta{=tex});
choose a documented default (commonly 1.0) and record it in the config
rather than leaving it implicit.
Epsilon-greedy exploration
\[
```{=tex}
\begin{cases}
\text{random valid action}, & \text{with probability }\epsilon_t\\
\arg\max_{a\in A_{\mathrm{valid}}(s_t)}Q_\theta(s_t,a), & \text{otherwise}
\end{cases}
```
\]
The supplied notes state that epsilon decays from 1.0 to 0.05. The exact
decay schedule must be recorded in the training config. Random
exploration is permitted only in the offline environment. On a deployed
Android build, use (\epsilon=0{=tex}) and always select the best valid
action.
16. Worked neural-network example
This example uses values reported in the uploaded experiment notes. It
is a single synthetic state evaluated by the exported seed-2
simulator-trained policy, not a real rider interaction.

The example state is:
attempt_number_normalized = 0.33
blank_transcript          = 0
word_count_normalized     = 0.60
best_place_match_score    = 0.42
MatchGate flags           = [0, 0, 1, 0]
candidate_exists          = 1
previous_action_one_hot   = [0, 0, 0, 0, 0]
saved_places_available   = 1
repeated_same_words       = 0
The uploaded output reports: - Hidden layer 1: 17 of 64 neurons active
after ReLU. - Hidden layer 2: 10 of 64 neurons active after ReLU. -
First hidden neuron pre-activation: (-1.7502); after ReLU: (0). -
Q-values:

  Action                   Reported Q-value
  NAME_REASON                       0.288
  OFFER_ALTERNATIVES                0.372
  OFFER_ESCAPES                     0.089
  CONFIRM_TOP_GUESS                 0.356
  OFFER_SAVED_PLACES                0.366

With all five actions valid, the highest Q-value is OFFER_ALTERNATIVES
(0.372). These Q-values are not probabilities and must not be displayed
as percentages or confidence scores.

The supplied notes also report that when the candidate-confirmation
action is masked, OFFER_ALTERNATIVES remains the selected action.
Antigravity must recreate this test from the actual exported model
artifact and verify the exact state encoding; do not hardcode these
Q-values into the application.
17. Simulator training results reported in the uploaded notes
Evidence label: SIMULATED ENVIRONMENT --- NOT VALIDATED ON REAL
RIDERS.

The supplied notes report that five seeds were trained for 40,000
episodes each. The environment is a synthetic speech/dialogue-error
simulator. The values below are transcribed from the uploaded notes and
have not been independently reproduced against the Cab Eye repository in
this documentation step.
17.1 Policy comparison
Reported evaluation results:
  Policy         Success rate Abandon rate  Escape rate   Mean turns  Mean return
  Fixed ladder        0.582 ±      0.305 ±      0.112 ±      1.872 ±     -0.038 ±
  (today)               0.003        0.004        0.002        0.005        0.006

  Random valid        0.540 ±      0.237 ±      0.223 ±      1.660 ±     -0.013 ±
                        0.004        0.005        0.003        0.004        0.008

  Always              0.412 ±      0.143 ±      0.444 ±      1.397 ±     -0.074 ±
  escalate              0.005        0.002        0.005        0.002        0.006

  Tabular Q           0.587 ±      0.236 ±      0.177 ±      1.686 ±      0.045 ±
                        0.022        0.017        0.039        0.049        0.010
  DQN                 0.609 ±      0.241 ±      0.150 ±      1.685 ±      0.070 ±
  (16-64-64-5)          0.011        0.004        0.013        0.017        0.011
The meaning of ± (standard deviation, standard error, or confidence
interval) is not identified in the uploaded notes. Antigravity must
inspect evaluate.py and the raw per-seed results and define it
accurately in any final report.
Interpretation limited to the simulator
Compared with the reported fixed ladder: - DQN success rate is higher by
0.027 (2.7 percentage points). - DQN mean turns are lower by 0.187. -
DQN abandonment rate is lower by 0.064. - DQN escape rate is higher by
0.038. - DQN mean return is higher by 0.108.

These are descriptive differences from the supplied simulated
evaluation. They do not establish statistical significance or real-world
benefit. In particular, the increase in escape rate must be interpreted
in context: it could mean the policy offers an alternative recovery path
more often, and it should not automatically be labeled good or bad
without the exact environment definitions.

The simulator's reported success rate of 0.609 is not a real-user
booking success rate. It must not be presented in a project
demonstration as a measured field outcome.
17.2 Training checkpoints reported
The uploaded notes report these five-seed averaged checkpoint values:
        Episode   Mean evaluation   Mean evaluation Reported training
                     success rate            return              loss
          2,000             0.583             0.027             0.198

         10,000             0.589             0.042             0.214

         20,000             0.609             0.071             0.221

         40,000             0.604             0.066             0.228
The uploaded notes also report elapsed training times per seed of 31.8,
31.7, 33.3, 32.0, and 32.5 seconds in that environment. These timings
are machine/environment-specific and should not be used as an Android
inference benchmark.

Training loss rises across the listed checkpoints while evaluation
success initially improves. This is not automatically a bug: TD loss
measures prediction error against a moving bootstrapped target, not
direct task success. The dashboard must plot loss and evaluation
performance separately and should not interpret a lower loss as proof of
a better policy.
17.3 Reported tests
The uploaded notes show this command:
python3 -m pytest tests -v
Reported result: 7 passed in 2.04 seconds.

Reported test names: 1. test_state_shape_and_range 2.
test_every_episode_terminates_and_exit_is_offered_by_attempt_3 3.
test_mask_rules 4. test_invalid_action_rejected 5.
test_seed_reproducible 6.
test_untrained_net_never_picks_masked_action 7.
test_exported_json_matches_network

The notes also report generation of live_snapshot.png,
training_curves.png, comparison.png, and action_mix.png, and an
exported recovery_policy_v1.json of approximately 120,119 bytes,
described as simulator-trained, seed 2; NOT validated on real riders.

Verification status: These are results transcribed from the supplied
notes. The original training workspace and its files have not been
independently rerun or verified as part of creating this Markdown file.
Antigravity must rerun the tests from the delivered project and replace
this status with the actual command output. Do not infer that the Cab
Eye Android app, Kotlin integration, live dashboard, or real-device
behavior has passed these Python simulator tests.
17.4 Required reproducibility checks
Before relying on these results: - Verify the exact simulator reward and
terminal definitions. - Confirm how evaluation means and ± values were
calculated. - Re-run all five seeds from a clean environment. - Verify
the policy comparison uses the same evaluation scenario distribution and
sample count for each policy. - Check that the test set is held out from
training. - Save configs, seeds, logs, JSONL metrics, checkpoint hashes,
and source revision. - Run action-mask and exported-weight parity
tests. - Record environment and Python/PyTorch versions. - Compare the
exported policy with Python outputs over a large set of seeded states,
not just one worked example.
18. Live dashboard and graph requirements --- concrete metrics
The dashboard must consume events from the actual training process and
persist the raw event stream. The dashboard must never manufacture a
curve or use the checkpoint table above as if a training run were
currently happening.
18.1 Required graphs
1. Episode reward: raw return per episode and rolling mean; show
   rolling-window size.
2. Evaluation success rate: periodic greedy evaluation on a fixed
   validation set, with uncertainty band only if computed correctly.
3. Mean evaluation return: separate from training reward.
4. TD loss: Huber loss sampled at the actual training interval.
5. Epsilon: exploration schedule from the active training config.
6. Mean dialogue turns: evaluation-only metric, with scenario
   count.
7. Outcome rates: success, abandon, escape, unresolved, and
   incorrect-destination acceptance, with explicit definitions.
8. Action distribution: counts and percentages for the five
   canonical actions.
9. Baseline comparison: fixed ladder, random-valid diagnostic,
   tabular Q diagnostic, and DQN where those policies are actually
   implemented.
10. Inference profile: p50/p95 latency and model size from measured
    runtime; Android results must be measured on a device.
11. Test results: actual test commands, passed/failed/skipped
    counts, duration, exit code, and failure excerpts.
12. Run logs: timestamped warnings, checkpoints, errors, and phase
    transitions.
18.2 Live event contract
An episode metric event may follow this schema:
{
  "run_id": "unique-run-id",
  "timestamp": "ISO-8601",
  "event_type": "episode_metrics",
  "phase": "training",
  "seed": 2,
  "episode": 2000,
  "environment_steps": 0,
  "episode_reward": null,
  "rolling_mean_reward": null,
  "evaluation_success_rate": 0.583,
  "evaluation_mean_return": 0.027,
  "td_loss": 0.198,
  "epsilon": null,
  "mean_turns": null,
  "elapsed_seconds": null,
  "environment_kind": "synthetic"
}
This is an illustrative schema populated with the reported
2,000-episode checkpoint values where available; other values are
intentionally null because the supplied checkpoint summary does not
establish them. Production events must use actual measured values and
actual timestamps. Do not copy this example as a fake event.

Requirements: - Emit events after each episode and after configurable
training-step intervals. - Emit evaluation events separately from
training events. - Persist JSONL/CSV history per run and include the
seed/config/source revision. - The dashboard updates without page
refresh. - Show last update time and connection status. - Reconnect and
backfill from persisted history after disconnect. - Separate different
run IDs and never merge curves from different runs accidentally. - Empty
metrics display "Waiting for training data". - Clearly label synthetic
environment and real-user evaluation as different evidence categories. -
When training stops or crashes, retain the last metrics and display the
actual terminal state.
18.3 Training controls
If the dashboard offers Start, Stop, and Evaluate controls: - Run the
trainer as a managed subprocess/job, not as arbitrary shell input from
the browser. - Restrict the service to localhost during development or
require authentication. - Validate configs and output paths. - Stop
gracefully at an episode/checkpoint boundary. - Keep separate run IDs
and separate artifact directories. - Do not expose training-control
endpoints to the public internet without security design.
19. Kotlin inference and export parity
The supplied notes propose a plain Kotlin forward pass using
FloatArray and weights exported to JSON. That is a viable lightweight
candidate, but it is not automatically correct just because the model is
small.

Required behavior: 1. Load and parse the exported weights once. 2.
Validate schema version, layer dimensions, finite weights, and expected
action names. 3. Apply the exact same feature order, missing-value
rules, clipping, and normalization as Python. 4. Compute two dense
layers with ReLU and a linear output layer. 5. Apply the valid-action
mask before selecting the action. 6. Return only the canonical action
ID. 7. Fall back to RecoveryLadder on load/parse error, invalid
dimensions, NaN/infinite values, invalid action, timeout, or stale
state. 8. Keep model loading and inference off the UI/audio critical
path. 9. Do not load a model or use DQN to bypass explicit destination
confirmation.
Parity test
For a fixed set of states, compare Python and Kotlin Q-values action by
action. Define an explicit numeric tolerance and fail the test if any
value exceeds it. Then compare the selected valid action, including
cases where actions are masked. Include boundary states, missing
optional features, no candidate, no saved place, and terminal states.

The single worked example in Section 16 is a smoke test, not sufficient
proof of parity.
20. Updated Antigravity execution plan
Phase 0 --- Inspect source and experiment artifacts
- Inspect the current Cab Eye checkout, RecoveryLadder.kt, its
  callers, MatchGate, saved-place APIs, state transitions, speech
  confidence, and tests.
- If the simulator code from the uploaded notes is available in the
  repository, inspect recovery_env.py, agents.py, train.py,
  evaluate.py, live_dashboard.py, tests, JSONL metrics, and
  exported weights.
- Confirm the exact four MatchGate flags and feature ordering.
- Confirm canonical action semantics and how OFFER_ESCAPES is
  implemented.
- Report exact paths and discrepancies before editing.
- Do not change booking, payment, driver assignment, SOS, or unrelated
  UI.
Phase 1 --- Freeze schema and baseline
- Define versioned 16-feature schema and five canonical action IDs.
- Add feature encoders and action masks with unit tests.
- Wrap existing RecoveryLadder behind the policy interface.
- Keep baseline behavior as the default.
- Verify no destination can be confirmed without the existing
  confirmation gate.
- Run existing Android tests and record actual results.
Phase 2 --- Reproducible Python DQN
- Implement/verify the 16-64-64-5 MLP, Double DQN target, replay
  buffer, Huber loss, Adam optimizer, epsilon schedule, and
  target-network refresh.
- Save config and random seeds.
- Verify state dimensions, action masks, terminal transitions, target
  calculations, reproducibility, checkpoint load, and export.
- Re-run the five-seed evaluation and produce raw metrics before
  integrating the model into Android.
Phase 3 --- Results and training dashboard
- Implement live event stream from the actual trainer.
- Persist JSONL/CSV per run.
- Add required charts, task metrics, action mix, test panel, status,
  and logs.
- Test reconnect, backfill, run separation, trainer failure, stop
  behavior, and empty state.
- Make simulated/real evidence labels prominent.
Phase 4 --- Kotlin inference and model integration
- Export versioned model and metadata.
- Implement Kotlin forward pass only after verifying exact parity.
- Add feature flag defaulting to baseline.
- Add safe fallback for every load/inference/state error.
- Test on the actual Android device and measure latency; do not infer
  device performance from Python timing.
Phase 5 --- Evaluation and release gate
- Compare DQN and baseline on identical held-out synthetic scenarios.
- Re-run Python/dashboard/Android tests.
- Produce REPORT.md, raw logs, JSONL/CSV metrics, test result JSON,
  chart exports, config, and model metadata.
- Clearly report success, abandon, escape, turns,
  incorrect-destination rate, uncertainty definition, and fallback
  rate.
- Do not enable DQN by default unless pre-defined criteria are met.
- Plan a consented and ethically appropriate usability study before
  making real-world accessibility effectiveness claims.
Mandatory report after every phase
Antigravity must report: - Files changed. - Commands actually
executed. - Exit codes and actual test summaries. - Training run IDs,
seeds, configuration and data source. - Graphs/metrics generated from
actual events. - Model artifact and schema versions. - Failures,
unresolved issues, and next step. - Clear labels separating synthetic
results, unit tests, device tests, and real-user evaluation.
21. Acceptance criteria
- [ ] Existing RecoveryLadder remains functional and is the
  default/fallback.
- [ ] 16-feature state schema is implemented from actual source data
  and versioned.
- [ ] Five canonical action IDs map to real, tested app behaviors.
- [ ] Invalid actions are masked in training, evaluation, and
  inference.
- [ ] Double DQN target, Huber loss, Adam update, replay buffer,
  epsilon schedule, and target updates are tested.
- [ ] Python training is reproducible across documented seeds.
- [ ] Evaluation metrics are calculated from actual environment
  outcomes.
- [ ] Reported results can be reproduced or differences are
  documented.
- [ ] Live graphs update from the real training process without
  refresh.
- [ ] Training, validation, test, synthetic, and real-user metrics are
  clearly distinguished.
- [ ] Test dashboard reflects actual commands, exit codes, and test
  reports.
- [ ] JSON export is validated and Kotlin Q-values match Python within
  defined tolerance.
- [ ] Missing/incompatible model or invalid state reliably falls back.
- [ ] Destination confirmation, SOS, accessibility, and state-machine
  regressions pass.
- [ ] Model size and Android inference latency are measured on the
  target device.
- [ ] Results report, raw logs, metrics, graphs, config, and model
  metadata are saved.
- [ ] No claim of real-user improvement is made from synthetic
  results.
- [ ] DQN remains disabled by default until reviewed and explicitly
  enabled.
22. Evidence statement for reports and presentations
Use wording like this until a real-user study has been completed:
"In a synthetic dialogue-recovery environment, the reported 16-64-64-5
Double DQN achieved a 0.609 success rate versus 0.582 for the fixed
ladder in the supplied evaluation, with mean turns of 1.685 versus
1.872. The supplied notes report seven passing Python simulator tests.
These results are preliminary, simulator-specific, and have not been
validated with real Cab Eye riders. Android integration, device-level
performance, and real-user accessibility outcomes require separate
verification."

Do not say: - "Cab Eye improved real-world booking success by 2.7%." -
"The DQN is production-ready." - "All Android tests passed" based only
on Python simulator tests. - "Real-time learning" if only the dashboard
is streaming metrics from offline training. The requested dashboard is
real-time monitoring of an offline training run, not online learning
from passengers.

Recommended first milestone: inspect the actual Cab Eye and
experiment source, confirm the 16 features and five actions, then freeze
the schema and add baseline regression tests before any model
integration.