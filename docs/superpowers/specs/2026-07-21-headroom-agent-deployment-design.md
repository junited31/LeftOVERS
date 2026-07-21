# Headroom Agent Deployment Design

## Goal

Run Headroom persistently on this ARM64 Ubuntu host and route Claude Code, Codex CLI, and OpenCode through it without replacing their existing credentials or unrelated configuration.

## Architecture

- Upgrade the existing `uv`-managed `headroom-ai` tool to the current release with only the `proxy` and `code` extras. The proxy extra already includes MCP support; GPU/ML extras are unnecessary on this host.
- Repair the installed OpenCode ARM64 binary by running its existing postinstall script; do not add another OpenCode installation.
- Use Headroom's turnkey deployment with Docker on `127.0.0.1:8787`. Pin `ghcr.io/headroomlabs-ai/headroom:0.32.0`, the current repository release with an ARM64 image, instead of the stale legacy image alias bundled as the PyPI default.
- Apply provider-scoped, reversible configuration only to `claude`, `codex`, and `opencode`.
- Launch sessions with `headroom wrap claude`, `headroom wrap codex`, or `headroom wrap opencode`; each wrapper reuses the persistent proxy.

## Data Flow

Each agent sends its normal provider request to the local Headroom proxy. Headroom compresses eligible context, forwards the request to the agent's existing upstream provider, and returns the response. Credentials remain in the existing agent/provider stores and are not copied into this repository.

## Safety and Recovery

- Preserve unrelated working-tree changes and agent settings.
- Rely on Headroom's managed mutations and OpenCode backup instead of hand-editing configuration.
- Keep the proxy loopback-only; do not expose port 8787 publicly.
- If deployment fails, report the failing stage and leave the reversible profile available for `headroom install remove` or the corresponding `headroom unwrap` command.

## Verification

The deployment is accepted when:

1. `headroom`, `claude`, `codex`, and `opencode` report versions successfully.
2. `headroom install status` reports the default profile running and healthy.
3. `http://127.0.0.1:8787/readyz` succeeds and `/health` identifies Headroom.
4. Headroom-managed configuration is present for Claude, Codex, and OpenCode without deleting pre-existing settings.
5. Each wrapper can reach its launch boundary without starting a second proxy; interactive agent sessions are not left running after the smoke check.

## Non-goals

No public ingress, shared multi-user service, new API keys, custom dashboard, memory/learning features, or repository application code are added.
