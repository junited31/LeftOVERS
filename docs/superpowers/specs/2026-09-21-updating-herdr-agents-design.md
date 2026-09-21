# Updating Herdr Agents Skill Design

## Goal

Create one personal skill, shared by Codex, Claude Code, and OMP, that safely updates the three agent CLIs used in every pane of the current Herdr session and then resumes the exact conversations that occupied those panes. A pane with no persisted conversation starts a new session.

## Installation

The canonical skill lives at `~/.agents/skills/updating-herdr-agents/`. Claude Code receives a symlink at `~/.claude/skills/updating-herdr-agents`; Codex and OMP use the canonical Agents skill directory. The skill contains `SKILL.md` and one executable helper script.

## Safety and Lifecycle Policy

The planner must verify `HERDR_ENV=1` before inspecting panes. It targets all workspaces and tabs on the current Herdr server, but only agents detected as `codex`, `claude`, or `omp`.

Preflight independently enumerates every server pane and inspects `herdr pane process-info` foreground argv and executable realpath. Any process that appears to be Codex, Claude Code, or OMP must map to exactly one Herdr agent with a safe lifecycle classification. An unrecognized, `launch_pending`, `unknown`, or otherwise unmatched candidate fails the entire run before mutation; detection failure never silently removes a pane from scope.

Planning runs inside the invoking agent and is read-only. Applying cannot run as that agent's child: waiting for or terminating the caller would deadlock or kill the helper. After explicit approval, the skill creates one dedicated Herdr shell pane and launches an independent supervisor there. The caller then returns so it can settle. The supervisor is the only component allowed to stop, update, and restart agents, including the caller. It may create and later close only its own coordinator pane.

The supervisor never interrupts `working` agents. It waits for them to settle, then revalidates every target immediately before mutation. OMP requires authoritative lifecycle-hook evidence. Claude Code and Codex may use matched screen-manifest lifecycle rules, but `default_known_agent_idle_fallback`, a missing matched rule, detection warnings, `unknown`, `blocked`, or any other unproven state is unsafe. The supervisor checks `herdr agent explain --json` immediately before every destructive action. The helper never answers prompts, clears drafts, sends `Ctrl-C` as a fallback, or approves dialogs.

## Inventory and Durable Journal

Preflight records, per pane:

- Herdr server/socket identity, workspace, tab, pane, and terminal IDs
- Herdr agent name and detected kind
- authoritative lifecycle source and state
- shell cwd and foreground process cwd
- stable launcher path, foreground executable realpath, argv, version, installation root/manager, release channel, and relevant config-home environment
- normalized persisted agent session ID or path and its integration source
- session classification: `RESUMABLE`, `PROVEN_EMPTY`, or `UNRESOLVED`

Missing session metadata is `UNRESOLVED`, not proof of an empty conversation. `UNRESOLVED` stops the run unless the user explicitly marks that pane as safe to replace during plan approval. A fresh session may be classified `PROVEN_EMPTY` only from authoritative integration evidence; otherwise it remains unresolved.

The Linux-only helper reads the foreground PID's `/proc/<pid>/environ` before exit to capture `CODEX_HOME`, `CLAUDE_CONFIG_DIR`, `PI_CODING_AGENT_DIR`, `PATH`, and other kind-specific launch context. If it cannot read or safely reproduce that context, preflight fails. The journal lives under `${XDG_STATE_HOME:-~/.local/state}/updating-herdr-agents/`; directories use mode `0700`, and journal, checkpoint, temporary atomic-replace, and backup files are created with mode `0600`. It records a versioned plan, inventory hash, server identity, target identities, explicit approval, and every transition. A server-scoped exclusive lock prevents concurrent runs. Each destructive transition uses `intent recorded and atomically fsynced -> side effect -> completion atomically fsynced`. Recovery resolves panes by server and terminal identity, then verifies current pane identity instead of trusting a stale workspace-qualified pane ID.

## Plan and Approval

`plan` verifies current Herdr integration status for every target kind, performs the independent all-pane completeness scan, validates installation and session identities, and prints the exact actions. It performs no mutation. The immutable plan hash covers stable target facts only: server, terminal and pane identity, kind, normalized session reference, installation identity, cwd/config context, and helper plan version. Transient lifecycle state and pane revision are observations, not hash inputs.

Approval is a separate operation over that immutable plan hash and records any approved new-session replacement plus an operator freeze against creating or replacing agent panes until apply completes. `apply` rejects a missing approval or changed stable target fact. `working -> proven-safe settled` is an expected transition; the supervisor discards plan-time lifecycle evidence and obtains fresh evidence before every destructive action. Any stable-fact mismatch requires a new plan and approval; apply never silently widens or substitutes targets.

## Transactional Update Flow

1. The skill runs read-only `plan` and presents every affected pane, session classification, installation identity, and updater.
2. The user explicitly approves the stable plan and any `PROVEN_EMPTY` or manually accepted unresolved pane, and agrees not to create, replace, or move agent panes until apply finishes.
3. The skill launches `apply` in a dedicated independent Herdr shell pane and returns control to the caller.
4. Before mutation, the supervisor subscribes to the Herdr event stream and buffers events, reruns the independent all-pane completeness scan, and requires the resulting target set to equal the approved set except for its own coordinator pane. It then waits for all `working` targets to settle and performs global safety revalidation. During apply, any pane creation, move, agent appearance, replacement, or newly detected Codex/Claude/OMP candidate is reconciled against the approved set before the next destructive action; an unapproved change stops the run.
5. Targets are processed one installation group at a time. By default, every pane of one agent kind must share the same stable launcher, installation root/manager, release channel, and config-home identity; otherwise preflight fails rather than guessing which installation to update.
6. Immediately before each group, revalidate `herdr agent explain --json`, session, launcher provenance, resolved executable, environment, cwd, server, terminal, and pane identity.
7. After the group has settled, the coordinator asks the operator to inspect and confirm that every group composer is empty and to refrain from target-pane input until restoration completes. It records each pane revision/snapshot at confirmation and aborts the group if observable input or state changes before exit.
8. Create a pre-exit emergency snapshot of every `RESUMABLE` target's native conversation artifact and required companion metadata in the durable journal area. Use a copy or reflink, never a hardlink; fsync the files and directory and record content hashes.
9. Gracefully exit only that group's agents using the agent-specific supported exit action. Verify foreground ownership with `herdr pane process-info`; matching prompt text is not sufficient.
10. After exit has flushed final session records, create a separate authoritative post-exit snapshot with the same copy/reflink, fsync, and hash guarantees. A missing or unverifiable pre-exit or post-exit snapshot stops the group before the updater runs.
11. Preserve the recorded release channel and installation mechanism. A manager-owned canonical launcher or a launcher inside the verified installation root may self-update. A foreign symlink, shared dispatcher, or arbitrary wrapper fails preflight; package-manager installs invoke the recorded manager directly. Never use the coordinator's bare `PATH` lookup:
   - Codex launcher or manager `update`
   - Claude launcher or manager `update`
   - OMP launcher or manager `update --check`, followed by `update` only when needed
12. Verify that the same installation root and canonical launcher now resolve to the expected new realpath and version. If provenance, channel, or installation mechanism cannot be preserved, fail before stopping the group.
13. Restore each pane's shell cwd and recorded launch environment when necessary, then restart through the verified post-update launcher identity. Resume only with the recorded explicit session ID or path; never use a recent-session selector.
14. Prove a fresh exact resume. A retained Herdr `agent_session` value alone is insufficient because it may predate restart. The helper verifies that the new foreground process received the explicit resume reference and independently verifies the active identity through that CLI version's native session store or a post-restart integration report with a freshness generation. Mark `resumed` only when the fresh native identity equals the approved checkpoint reference. If the installed CLI/version offers no freshness proof, the pane is `failed`, never silently accepted.
15. Start a new session only for an approved `PROVEN_EMPTY` or explicitly approved replacement. Report each pane as `resumed`, `new`, or `failed`.
16. Complete recovery for the current installation group before proceeding to the next, limiting the blast radius of an updater failure.

## Component Boundaries

`SKILL.md` owns triggering rules, plan review, explicit approval, unresolved-session decisions, and recovery guidance.

The helper owns deterministic mechanics: Herdr JSON parsing, authoritative-state checks, durable journal transitions, installation grouping, updater execution, and exact restart verification. It must not approve its own plan, answer agent prompts, clear drafts, change release channels, alter repositories or agent configuration, or create/close any pane except its dedicated coordinator pane.

## Failure Handling

Planning failures cause no mutation. Apply failures preserve the durable journal, immutable conversation backups, and recovery evidence while recovering the current installation group pane by pane. Backups are never automatically written over native stores; they remain hashed operator recovery artifacts. One failed pane does not prevent recovery attempts for its stopped siblings, but no later installation group starts. A missing recorded session, changed target, or resume-reference mismatch is reported as failed rather than attached to another conversation.

Recovery reconciles every intent-only transition from observed state before retrying. For backup: both pre-exit and post-exit required files, fsync completion, and hashes must verify before update. For exit: shell foreground ownership completes the transition; the original agent requires a fresh safety check before retry; any third process fails. For update: the recorded launcher at the expected post-version completes the transition; the exact pre-version permits one retry; any other installation/version is ambiguous and fails. For restart: an existing target agent completes only after fresh native session-identity proof; shell ownership permits restart; any third process fails. An intent record or retained Herdr session value alone never authorizes replay or completion.

## Verification

Skill behavior is developed with RED-GREEN-REFACTOR pressure scenarios. Baselines must expose unsafe shortcuts including trusting a plan-time-only pane inventory, self-deadlock, treating missing metadata as empty, hashing transient lifecycle state, trusting unmatched screen fallback, assuming a plan-time empty composer remains empty, using `--last`, interrupting work, updating a foreign launcher, losing per-process config homes, backing up only before shutdown flush, and accepting stale Herdr session metadata as a fresh resume.

The helper is verified in an isolated named Herdr test session with fake `codex`, `claude`, and `omp` executables. Scenarios cover detection-missed foreground processes, new/replaced/moved agent panes between plan and apply and during apply, caller-pane handoff, stable-field plan approval, expected working-to-idle transitions, final empty-composer confirmation and subsequent pane changes, accepted manifest rules versus unsafe fallback, blocked preflight, unresolved sessions, distinct hashed pre-exit and post-exit native session snapshots, final-record flush during graceful exit, updater deletion or migration of the original store, stale versus fresh session reports, explicit resume argv plus native active-session identity, approved new sessions, canonical and foreign launcher provenance, per-process config homes, updater failure, intent-only reconciliation, and group-scoped blast radius. The live environment receives only a read-only plan smoke test; verification must not update installed CLIs or terminate current sessions.
