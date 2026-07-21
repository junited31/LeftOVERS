# Headroom Agent Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run a persistent local Headroom proxy and route Claude Code, Codex CLI, and OpenCode through it.

**Architecture:** Keep the host CLI in its existing isolated `uv` tool environment and let Headroom manage a restartable Docker proxy on loopback port 8787. Use Headroom's provider-scoped reversible mutations for the three agents and its wrappers only as launchers that reuse the deployed proxy.

**Tech Stack:** Ubuntu 24.04 ARM64, Python 3.13 via `uv`, Docker, Headroom, Claude Code, Codex CLI, OpenCode.

## Global Constraints

- Preserve existing credentials and unrelated agent configuration.
- Bind the proxy only to `127.0.0.1:8787`.
- Configure only `claude`, `codex`, and `opencode`.
- Do not enable telemetry, memory, learning, public ingress, or repository application code.
- Do not touch the existing untracked `.omo` files.

---

### Task 1: Repair the agent toolchain and upgrade Headroom

**Files:**
- Modify externally: `/home/ai/.nvm/versions/node/v24.15.0/lib/node_modules/opencode-ai/bin/opencode.exe`
- Modify externally: `/home/ai/.local/share/uv/tools/headroom-ai/`

**Interfaces:**
- Consumes: existing global OpenCode 1.18.4 package and `uv` tool installation.
- Produces: runnable `opencode` and Headroom 0.32.1 CLI commands.

- [ ] **Step 1: Prove OpenCode is currently broken**

Run: `opencode --version`

Expected: non-zero exit with `opencode-ai's postinstall script was not run`.

- [ ] **Step 2: Run the installed package's native repair**

Run: `cd "$(npm root -g)/opencode-ai" && node postinstall.mjs`

Expected: exit 0 and an ARM64 binary at `bin/opencode.exe`.

- [ ] **Step 3: Verify OpenCode**

Run: `opencode --version`

Expected: exit 0 and version `1.18.4`.

- [ ] **Step 4: Remove the failed download cache**

Run: `uv cache clean`

Expected: exit 0 and enough free disk for the minimal install.

- [ ] **Step 5: Install only the required Headroom features**

Run: `uv tool install --force --python 3.13 "headroom-ai[proxy,code]==0.32.1"`

Expected: exit 0 with the `headroom` executable installed and no Torch/CUDA packages.

- [ ] **Step 6: Verify all host CLIs**

Run: `headroom --version && claude --version && codex --version && opencode --version`

Expected: four zero exits; Headroom reports `0.32.1`.

### Task 2: Deploy and configure Headroom

**Files:**
- Modify externally: `/home/ai/.headroom/deploy/default/manifest.json`
- Modify externally: `/home/ai/.claude/settings.json`
- Modify externally: `/home/ai/.codex/config.toml`
- Create externally: `/home/ai/.config/opencode/opencode.json`
- Create externally when needed: `/home/ai/.config/opencode/opencode.json.headroom-backup`

**Interfaces:**
- Consumes: Headroom 0.32.1 CLI, Docker, and the three installed agent CLIs.
- Produces: healthy local proxy at `http://127.0.0.1:8787` and reversible provider routing.

- [ ] **Step 1: Capture non-secret pre-deployment invariants**

Run:

```bash
python3 - <<'PY'
import json
from pathlib import Path

claude = json.loads(Path.home().joinpath('.claude/settings.json').read_text())
assert claude['permissions']['defaultMode'] == 'auto'
assert claude['model'] == 'opus'
assert 'hooks' in claude
codex = Path.home().joinpath('.codex/config.toml').read_text()
assert 'model = "gpt-5.6-sol"' in codex
assert '[features]' in codex
opencode = Path.home().joinpath('.config/opencode/opencode.jsonc').read_text()
assert 'oh-my-openagent@latest' in opencode
print('pre-deployment invariants: ok')
PY
```

Expected: `pre-deployment invariants: ok`.

- [ ] **Step 2: Create the persistent deployment and provider wiring**

Run:

```bash
headroom deploy \
  --scope provider \
  --providers manual \
  --target claude \
  --target codex \
  --target opencode \
  --no-telemetry
```

Expected: Headroom selects `persistent-docker`, starts the default profile, and reports the three managed targets.

- [ ] **Step 3: Verify lifecycle and health endpoints**

Run:

```bash
headroom install status
curl --fail --silent http://127.0.0.1:8787/readyz
curl --fail --silent http://127.0.0.1:8787/health | python3 -m json.tool
```

Expected: the profile is running and healthy, `readyz` exits 0, and health JSON identifies the default persistent Docker deployment.

### Task 3: Verify routing and wrapper reuse

**Files:**
- Verify externally: `/home/ai/.claude/settings.json`
- Verify externally: `/home/ai/.codex/config.toml`
- Verify externally: `/home/ai/.config/opencode/opencode.json`

**Interfaces:**
- Consumes: the persistent deployment from Task 2.
- Produces: evidence that all three managed configs and wrappers use the same proxy.

- [ ] **Step 1: Check managed configuration without printing credentials**

Run:

```bash
python3 - <<'PY'
import json
from pathlib import Path

home = Path.home()
claude = json.loads((home / '.claude/settings.json').read_text())
assert claude['env']['ANTHROPIC_BASE_URL'] == 'http://127.0.0.1:8787'
assert claude['permissions']['defaultMode'] == 'auto'
assert claude['model'] == 'opus'
assert 'hooks' in claude

codex = (home / '.codex/config.toml').read_text()
assert '# --- Headroom persistent provider ---' in codex
assert 'model_provider = "headroom"' in codex
assert 'base_url = "http://127.0.0.1:8787/v1"' in codex
assert 'model = "gpt-5.6-sol"' in codex
assert '[features]' in codex

opencode = json.loads((home / '.config/opencode/opencode.json').read_text())
assert opencode['provider']['headroom']['options']['baseURL'] == 'http://127.0.0.1:8787/v1'
assert 'oh-my-openagent@latest' in (home / '.config/opencode/opencode.jsonc').read_text()
print('managed routing and preserved settings: ok')
PY
```

Expected: `managed routing and preserved settings: ok`.

- [ ] **Step 2: Smoke-test each wrapper without leaving an interactive session**

Run:

```bash
timeout 45 headroom wrap claude -- --version
timeout 45 headroom wrap codex -- --version
timeout 45 headroom wrap opencode -- --version
```

Expected: each command exits 0, prints its agent version, and reports reuse of the existing proxy rather than binding a second proxy.

- [ ] **Step 3: Re-check the persistent runtime after wrapper exits**

Run: `headroom install status && curl --fail --silent http://127.0.0.1:8787/readyz`

Expected: the default profile remains running and healthy.

- [ ] **Step 4: Confirm repository scope stayed clean**

Run: `git status --short`

Expected: only the committed design/plan history and the user's pre-existing untracked `.omo` paths; no generated application or secret files.
