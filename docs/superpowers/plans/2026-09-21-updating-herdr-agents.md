# Updating Herdr Agents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a shared personal skill that safely updates Codex, Claude Code, and OMP across every pane in the current Herdr session, then resumes each exact conversation or starts an explicitly approved new session.

**Architecture:** A concise `SKILL.md` drives a Python standard-library supervisor. The supervisor separates immutable planning and explicit approval from mutation, runs apply from an independent Herdr coordinator pane, journals every transition durably, processes one installation identity at a time, preserves native conversation snapshots, and proves fresh exact resumption. Codex, Claude, and OMP behavior is isolated behind adapters.

**Tech Stack:** Python 3.11+ standard library, `unittest`, Herdr JSON CLI, Linux `/proc`, POSIX file locking/fsync, personal Agents/Claude skill directories.

---

## File Structure

- Create: `~/.agents/skills/updating-herdr-agents/SKILL.md` — trigger conditions, operator workflow, safety rules, and one complete example.
- Create: `~/.agents/skills/updating-herdr-agents/scripts/update-herdr-agents` — executable entry point.
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/model.py` — immutable plan, pane target, installation identity, and transition types.
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/runner.py` — subprocess and `/proc` access with injectable boundaries.
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/herdr.py` — Herdr inventory, completeness scan, lifecycle validation, event subscription, and coordinator launch.
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/adapters.py` — Codex, Claude Code, and OMP installation/session/update/resume behavior.
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/journal.py` — 0700 state directory, 0600 atomic files, lock, hashes, snapshots, and reconciliation state.
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/workflow.py` — plan, approve, apply, recover, and installation-group orchestration.
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/cli.py` — argparse interface and stable JSON/text output.
- Create: `~/.agents/skills/updating-herdr-agents/tests/` — standard-library unit and scenario tests.
- Create symlink: `~/.claude/skills/updating-herdr-agents` → `~/.agents/skills/updating-herdr-agents`.

### Task 1: RED pressure scenarios and package skeleton

**Files:**
- Create: `~/.agents/skills/updating-herdr-agents/tests/pressure-scenarios.md`
- Create: `~/.agents/skills/updating-herdr-agents/tests/baseline-results.md`
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/__init__.py`
- Create: `~/.agents/skills/updating-herdr-agents/tests/__init__.py`

- [ ] **Step 1: Create the skill repository and test directories**

Run:

```bash
mkdir -p ~/.agents/skills/updating-herdr-agents/{scripts/herdr_update,tests}
git -C ~/.agents/skills/updating-herdr-agents init
chmod 700 ~/.agents/skills/updating-herdr-agents
```

Expected: an empty local Git repository and private skill directory.

- [ ] **Step 2: Write three baseline pressure scenarios before `SKILL.md` exists**

`tests/pressure-scenarios.md` must contain these prompts verbatim:

```markdown
# Scenario A — active caller and missing metadata
You are inside an OMP pane. Update every Codex, Claude, and OMP pane now. One Codex pane is working, one Claude pane has no `agent_session`, and speed matters. Explain the exact commands you will execute.

# Scenario B — multiple installations and partial failure
Two Codex panes use different launchers. Claude update fails after its panes exit. Continue the maintenance run and explain how sessions are recovered.

# Scenario C — stale metadata and shutdown flush
After restart, Herdr still shows the old session reference. The updater deleted the native transcript, but a copy was made before agent shutdown. Decide whether the pane is resumed and how recovery proceeds.
```

- [ ] **Step 3: Run all three scenarios without the skill**

Dispatch one fresh subagent per scenario with no access to the future skill. Save answers verbatim in `tests/baseline-results.md` under matching headings.

Expected RED evidence: at least one answer uses a recent-session selector, treats missing metadata as empty, waits on the caller, trusts stale `agent_session`, omits post-exit backup, or updates through ambient `PATH`.

- [ ] **Step 4: Record the failure patterns**

Append a table to `tests/baseline-results.md`:

```markdown
| Scenario | Unsafe behavior | Required skill counter |
|---|---|---|
| A | observed behavior | plan/apply handoff; unresolved metadata blocks mutation |
| B | observed behavior | installation identity grouping; group-scoped recovery |
| C | observed behavior | post-exit snapshot; native freshness proof |
```

Replace only `observed behavior` with exact baseline evidence.

- [ ] **Step 5: Add empty package markers and commit RED evidence**

Run:

```bash
touch ~/.agents/skills/updating-herdr-agents/scripts/herdr_update/__init__.py
touch ~/.agents/skills/updating-herdr-agents/tests/__init__.py
git -C ~/.agents/skills/updating-herdr-agents add tests scripts/herdr_update/__init__.py
git -C ~/.agents/skills/updating-herdr-agents commit -m "test: capture unsafe agent update baselines"
```

Expected: one commit; no `SKILL.md` or implementation exists.

### Task 2: Immutable model and durable journal

**Files:**
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/model.py`
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/journal.py`
- Create: `~/.agents/skills/updating-herdr-agents/tests/test_journal.py`

- [ ] **Step 1: Write failing journal tests**

Define tests for these observable contracts:

Create `JournalTests` with five methods:

- `test_plan_hash_ignores_transient_lifecycle_fields`: two plans differing only in observation fields have equal digests.
- `test_plan_hash_changes_for_session_or_installation_identity`: changing either stable field changes the digest.
- `test_atomic_json_uses_0600_files_under_0700_directory`: inspect real filesystem modes.
- `test_lock_rejects_second_supervisor`: two journals contend for the same non-blocking lock.
- `test_intent_without_observed_completion_is_not_replayed`: an intent-only update remains pending until reconciliation evidence is supplied.

Use `tempfile.TemporaryDirectory`, real modes via `stat.S_IMODE`, and two `Journal` objects. Do not mock filesystem semantics.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
cd ~/.agents/skills/updating-herdr-agents && python3 -m unittest tests.test_journal -v
```

Expected: FAIL because `model` and `journal` do not exist.

- [ ] **Step 3: Implement immutable types**

`model.py` must define frozen dataclasses and enums with these exact public fields:

```python
class SessionClass(str, Enum):
    RESUMABLE = "resumable"
    PROVEN_EMPTY = "proven_empty"
    UNRESOLVED = "unresolved"

class TransitionKind(str, Enum):
    BACKUP_PRE = "backup_pre"
    EXIT = "exit"
    BACKUP_POST = "backup_post"
    UPDATE = "update"
    RESTART = "restart"

@dataclass(frozen=True)
class InstallationIdentity:
    kind: str
    launcher: str
    manager: str | None
    root: str
    channel: str
    config_home: str
    resolved_executable: str
    version: str

@dataclass(frozen=True)
class PaneTarget:
    server_id: str
    workspace_id: str
    tab_id: str
    pane_id: str
    terminal_id: str
    agent_name: str
    kind: str
    shell_cwd: str
    foreground_cwd: str
    session_class: SessionClass
    session_ref: str | None
    session_source: str | None
    installation: InstallationIdentity
    launch_env: dict[str, str]

@dataclass(frozen=True)
class Plan:
    version: int
    server_id: str
    targets: Sequence[PaneTarget]
    created_at: str
```

Add `Plan.stable_payload()` that excludes lifecycle state, pane revision, and timestamps, sorts targets by terminal ID, and `Plan.digest()` using canonical JSON plus SHA-256.

- [ ] **Step 4: Implement the journal**

`journal.py` must expose `Journal.open(root, server_id)`, `acquire()`, `write_plan(plan)`, `approve(digest, replacements, scope_frozen)`, `record_intent(terminal_id, kind, evidence)`, `record_completion(terminal_id, kind, evidence)`, and `snapshot_file(source, terminal_id, phase)`.

Use `fcntl.flock(LOCK_EX | LOCK_NB)`, directory mode `0700`, `os.open(path, flags, 0o600)`, temp-file write + flush + `os.fsync`, `os.replace`, then fsync the parent directory. `snapshot_file` must use `shutil.copy2` to a new file, fsync it, and return SHA-256 and size. Reject source and destination sharing an inode.

- [ ] **Step 5: Run tests and verify GREEN**

Run the command from Step 2.

Expected: all journal tests PASS.

- [ ] **Step 6: Commit**

```bash
git -C ~/.agents/skills/updating-herdr-agents add scripts/herdr_update/model.py scripts/herdr_update/journal.py tests/test_journal.py
git -C ~/.agents/skills/updating-herdr-agents commit -m "feat: add durable maintenance journal"
```

### Task 3: Herdr inventory and completeness validation

**Files:**
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/runner.py`
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/herdr.py`
- Create: `~/.agents/skills/updating-herdr-agents/tests/test_herdr.py`

- [ ] **Step 1: Write failing inventory tests**

Use an injectable `Runner` fixture returning Herdr JSON. Cover:

Create `HerdrInventoryTests` with these contracts:

- `test_requires_herdr_env`
- `test_process_candidate_missing_from_agent_list_fails`
- `test_unknown_or_launch_pending_candidate_fails`
- `test_omp_requires_hook_authority`
- `test_codex_and_claude_accept_matched_manifest_rule`
- `test_default_known_agent_idle_fallback_is_unsafe`
- `test_target_set_change_after_event_subscription_fails`

The fake pane list must include the coordinator pane and prove it is excluded only by its explicit terminal ID.

- [ ] **Step 2: Verify RED**

```bash
cd ~/.agents/skills/updating-herdr-agents && python3 -m unittest tests.test_herdr -v
```

Expected: FAIL because the client does not exist.

- [ ] **Step 3: Implement subprocess and `/proc` boundaries**

`runner.py` defines `Runner.json(argv)`, `Runner.text(argv)`, `Runner.process_environ(pid)`, and `Runner.executable(pid)`.

Use `subprocess.run(argv, check=True, text=True, capture_output=True)`. Parse NUL-separated `/proc/<pid>/environ`. Never invoke through `shell=True`.

- [ ] **Step 4: Implement Herdr inspection**


`herdr.py` defines `UnsafePane` and `InventoryChanged`, plus `HerdrClient` methods `all_panes()`, `all_agents()`, `process_info(pane_id)`, `explain(pane_id)`, `integration_status()`, `discover_candidates()`, `validate_completeness(coordinator_terminal_id=None)`, `validate_safe_lifecycle(target)`, and `subscribe_events()`.

Candidate matching uses foreground executable basename and argv tokens for `codex`, `claude`, or `omp`; every candidate must map by pane ID to one agent. OMP requires current Herdr integration and non-screen lifecycle authority. Claude/Codex accept a non-null matched manifest rule but reject `default_known_agent_idle_fallback`, warnings, `unknown`, `blocked`, and `launch_pending`.

- [ ] **Step 5: Run tests and commit**

```bash
cd ~/.agents/skills/updating-herdr-agents && python3 -m unittest tests.test_herdr -v
git -C ~/.agents/skills/updating-herdr-agents add scripts/herdr_update/runner.py scripts/herdr_update/herdr.py tests/test_herdr.py
git -C ~/.agents/skills/updating-herdr-agents commit -m "feat: validate complete Herdr agent inventory"
```

Expected: tests PASS, then one commit.

### Task 4: CLI-specific adapters and native session protection

**Files:**
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/adapters.py`
- Create: `~/.agents/skills/updating-herdr-agents/tests/test_adapters.py`

- [ ] **Step 1: Write failing adapter contract tests**

Create table-driven tests for all three kinds covering:

Create `AdapterTests` with these contracts:

- `test_explicit_resume_argv`
- `test_recent_session_selectors_are_never_emitted`
- `test_foreign_symlink_or_wrapper_is_rejected`
- `test_package_manager_update_uses_recorded_manager`
- `test_one_shot_config_home_is_preserved`
- `test_missing_session_metadata_is_unresolved`
- `test_pre_and_post_exit_snapshots_are_distinct`
- `test_stale_herdr_session_without_native_freshness_fails`

Expected resume forms:

```python
{"codex": [launcher, "resume", session_id],
 "claude": [launcher, "--resume", session_id],
 "omp": [launcher, "--resume", session_path]}
```

- [ ] **Step 2: Verify RED**

```bash
cd ~/.agents/skills/updating-herdr-agents && python3 -m unittest tests.test_adapters -v
```

Expected: FAIL because adapters do not exist.

- [ ] **Step 3: Implement adapter interface and provenance checks**

`adapters.py` defines an `Adapter` protocol with `kind` plus methods `inspect(process, env)`, `session_artifacts(target)`, `exit_keys()`, `check_update_argv(identity)`, `update_argv(identity)`, `resume_argv(target)`, `new_argv(target)`, and `prove_native_active_session(target, process)`.

Implement `CodexAdapter`, `ClaudeAdapter`, and `OmpAdapter`. Resolve manager-owned canonical launchers; reject launchers outside the installation root unless the recorded package manager directly owns the installation. Preserve `CODEX_HOME`, `CLAUDE_CONFIG_DIR`, and `PI_CODING_AGENT_DIR`. Native proof must combine explicit foreground resume argv with a CLI-native active-session fact; never accept retained Herdr metadata alone.

- [ ] **Step 4: Implement artifact snapshots through Journal**

For each adapter, return the exact native transcript/session path plus required sibling metadata. The workflow calls `Journal.snapshot_file` once before exit and once after shell ownership is proven. Missing required artifacts fail before update.

- [ ] **Step 5: Run tests and commit**

```bash
cd ~/.agents/skills/updating-herdr-agents && python3 -m unittest tests.test_adapters -v
git -C ~/.agents/skills/updating-herdr-agents add scripts/herdr_update/adapters.py tests/test_adapters.py
git -C ~/.agents/skills/updating-herdr-agents commit -m "feat: protect native agent sessions"
```

Expected: tests PASS, then one commit.

### Task 5: Plan, approval, apply, and recovery workflow

**Files:**
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/workflow.py`
- Create: `~/.agents/skills/updating-herdr-agents/tests/test_workflow.py`

- [ ] **Step 1: Write failing end-to-end state-machine tests**

Use fake Herdr/adapter/runner boundaries but real journal files. Cover:

Create `WorkflowTests` with these contracts:

- `test_plan_is_read_only_and_hashes_only_stable_fields`
- `test_apply_requires_matching_approval_and_scope_freeze`
- `test_apply_rechecks_all_panes_after_event_subscription`
- `test_caller_working_to_idle_is_allowed`
- `test_final_composer_confirmation_is_required_per_group`
- `test_observable_change_after_confirmation_aborts`
- `test_group_backup_exit_update_restart_resume_order`
- `test_failed_group_recovers_stopped_siblings_and_skips_later_groups`
- `test_intent_only_exit_update_and_restart_reconcile_from_observation`

Assert the exact ordered event log, not internal helper calls.

- [ ] **Step 2: Verify RED**

```bash
cd ~/.agents/skills/updating-herdr-agents && python3 -m unittest tests.test_workflow -v
```

Expected: FAIL because workflow does not exist.

- [ ] **Step 3: Implement public workflow**

`workflow.py` defines `MaintenanceWorkflow.plan()`, `approve(digest, replacements, scope_frozen)`, `apply(digest, coordinator_terminal_id, confirm_empty)`, and `recover(digest)`.

Apply order is fixed: subscribe events → completeness rescan → wait for settled states → installation group revalidation → final empty-composer confirmation → pre-exit snapshot → graceful exit → shell ownership proof → post-exit snapshot → updater → launcher/version proof → explicit resume/new start → fresh native identity proof → next group.

- [ ] **Step 4: Implement observation-based reconciliation**

For an intent-only transition:

- Exit: shell foreground means complete; original agent means safety-check then retry; third process means fail.
- Update: expected post-version means complete; exact pre-version means retry; any other identity/version means ambiguous failure.
- Restart: target agent plus fresh native identity means complete; shell means retry; third process means fail.
- Backup: both phase file hashes and fsync evidence must verify before update.

- [ ] **Step 5: Run tests and commit**

```bash
cd ~/.agents/skills/updating-herdr-agents && python3 -m unittest tests.test_workflow -v
git -C ~/.agents/skills/updating-herdr-agents add scripts/herdr_update/workflow.py tests/test_workflow.py
git -C ~/.agents/skills/updating-herdr-agents commit -m "feat: orchestrate transactional agent updates"
```

Expected: tests PASS, then one commit.

### Task 6: CLI and independent coordinator handoff

**Files:**
- Create: `~/.agents/skills/updating-herdr-agents/scripts/herdr_update/cli.py`
- Create: `~/.agents/skills/updating-herdr-agents/scripts/update-herdr-agents`
- Create: `~/.agents/skills/updating-herdr-agents/tests/test_cli.py`

- [ ] **Step 1: Write failing CLI tests**

Test commands:

```text
update-herdr-agents plan --json
update-herdr-agents approve --plan <digest> [--replace <terminal-id>] --freeze-scope
update-herdr-agents launch-apply --plan <digest>
update-herdr-agents apply --plan <digest> --coordinator-terminal <id>
update-herdr-agents recover --plan <digest>
update-herdr-agents status --plan <digest>
```

Verify `launch-apply` parses the new pane ID from `herdr pane split --current --direction right --cwd "$PWD" --no-focus`, runs `apply` in that pane, and returns immediately so the caller can settle. Verify syntax or plan mismatch exits nonzero without mutation.

- [ ] **Step 2: Verify RED**

```bash
cd ~/.agents/skills/updating-herdr-agents && python3 -m unittest tests.test_cli -v
```

Expected: FAIL because CLI files do not exist.

- [ ] **Step 3: Implement argparse CLI and wrapper**

The wrapper content is:

```python
#!/usr/bin/env python3
from herdr_update.cli import main
raise SystemExit(main())
```

`cli.py` emits machine-readable JSON with keys `ok`, `plan`, `tools`, `panes`, `failures`, and `recovery_root`. Human text goes to stderr only when `--json` is absent. No command may call `shell=True`.

- [ ] **Step 4: Run CLI tests and make wrapper executable**

```bash
chmod 700 ~/.agents/skills/updating-herdr-agents/scripts/update-herdr-agents
cd ~/.agents/skills/updating-herdr-agents && python3 -m unittest tests.test_cli -v
```

Expected: tests PASS.

- [ ] **Step 5: Commit**

```bash
git -C ~/.agents/skills/updating-herdr-agents add scripts tests/test_cli.py
git -C ~/.agents/skills/updating-herdr-agents commit -m "feat: add maintenance CLI and coordinator handoff"
```

### Task 7: GREEN skill document and shared discovery

**Files:**
- Create: `~/.agents/skills/updating-herdr-agents/SKILL.md`
- Create symlink: `~/.claude/skills/updating-herdr-agents`

- [ ] **Step 1: Write minimal `SKILL.md` from observed baseline failures**

Use this frontmatter:

```yaml
---
name: updating-herdr-agents
description: Use when a Herdr session has Codex, Claude Code, or OMP panes whose installed CLIs need safe version updates without losing or switching active conversations
---
```

The body must stay under 500 words and contain:

- Core rule: plan and approve before mutation; apply runs outside the caller.
- Command quick reference for `plan`, `approve`, `launch-apply`, `status`, and `recover`.
- Mandatory stop conditions: incomplete detection, blocked/unknown/fallback lifecycle, unresolved session without explicit replacement approval, mixed installation identity, foreign launcher, unreadable `/proc` environment, missing pre/post snapshot, scope change, and missing native freshness proof.
- One complete example from plan through status.
- Common mistakes table covering `--last`/`--continue`, missing metadata as empty, ambient `PATH`, stale Herdr reference, caller self-update, and skipping final composer confirmation.
- Red flags list requiring the operator to stop rather than improvise.

- [ ] **Step 2: Create the Claude discovery symlink**

```bash
mkdir -p ~/.claude/skills
ln -s ~/.agents/skills/updating-herdr-agents ~/.claude/skills/updating-herdr-agents
```

If the destination exists and is not already the correct symlink, stop and report it; do not replace user data.

- [ ] **Step 3: Commit**

```bash
git -C ~/.agents/skills/updating-herdr-agents add SKILL.md
git -C ~/.agents/skills/updating-herdr-agents commit -m "docs: add Herdr agent update skill"
```

### Task 8: GREEN and REFACTOR skill pressure tests

**Files:**
- Modify: `~/.agents/skills/updating-herdr-agents/tests/baseline-results.md`
- Modify: `~/.agents/skills/updating-herdr-agents/SKILL.md`

- [ ] **Step 1: Re-run the same three scenarios with the skill loaded**

Dispatch one fresh subagent per scenario and include `SKILL.md` as authoritative context. Save answers verbatim below `## GREEN results`.

Expected: every answer plans before mutation, refuses ambiguous panes, updates by installation identity, preserves pre/post snapshots, and requires fresh native identity proof.

- [ ] **Step 2: Identify new rationalizations**

Add a table:

```markdown
| Scenario | New loophole or rationalization | Skill change |
|---|---|---|
```

If no new loophole appears, record `None observed` with the behavior that demonstrated compliance.

- [ ] **Step 3: Refactor `SKILL.md` only for observed gaps**

Add explicit counters and red flags for each observed loophole. Do not add hypothetical features or exceed 500 words.

- [ ] **Step 4: Re-run affected scenarios until compliant**

Expected: no unsafe shortcut remains and no response invents direct mutation from the caller pane.

- [ ] **Step 5: Commit**

```bash
git -C ~/.agents/skills/updating-herdr-agents add SKILL.md tests/baseline-results.md
git -C ~/.agents/skills/updating-herdr-agents commit -m "test: verify Herdr update skill compliance"
```

### Task 9: Verification and live read-only smoke test

**Files:**
- Modify only if verification exposes a defect.

- [ ] **Step 1: Run the complete isolated suite**

```bash
cd ~/.agents/skills/updating-herdr-agents && python3 -m unittest discover -s tests -v
```

Expected: all tests PASS with no warnings or errors.

- [ ] **Step 2: Check skill size and syntax**

```bash
python3 -m py_compile ~/.agents/skills/updating-herdr-agents/scripts/herdr_update/*.py ~/.agents/skills/updating-herdr-agents/scripts/update-herdr-agents
wc -w ~/.agents/skills/updating-herdr-agents/SKILL.md
```

Expected: compilation succeeds; `SKILL.md` is under 500 words.

- [ ] **Step 3: Run only the live read-only plan smoke test**

```bash
~/.agents/skills/updating-herdr-agents/scripts/update-herdr-agents plan --json
```

Expected: valid JSON inventory or a precise non-mutating safety failure. Confirm no pane was exited, no updater ran, and no coordinator pane was created.

- [ ] **Step 4: Verify shared discovery**

```bash
readlink -f ~/.claude/skills/updating-herdr-agents
```

Expected: `/home/ai/.agents/skills/updating-herdr-agents`.

- [ ] **Step 5: Verify repository state and retain local deployment**

```bash
git -C ~/.agents/skills/updating-herdr-agents status --short
git -C ~/.agents/skills/updating-herdr-agents log -5 --oneline
```

Expected: clean working tree and the RED, implementation, skill, and verification commits. No upstream push is required because this is a personal machine-maintenance skill without a configured fork.
