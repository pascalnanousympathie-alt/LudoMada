# LudoMada Game Development Studio boundary

This directory records the verified integration state for the upstream Game Development Studio tools.

## Current state (verified 2026-10-12)

- Project-specific LudoMada agent skill: installed in this repository at `.agents/skills/ludomada-game-development-studio/SKILL.md`.
- Upstream `game-dev` CLI: release **1.5.0**, installed outside the checkout at `$HOME/.local/share/GameDevelopmentStudio/cli`.
- Release artifact integrity: tarball SHA-256 matched the official `SHA256SUMS.txt`.
- Termux command wrapper: `$PREFIX/bin/game-dev` and `$PREFIX/bin/game-dev-mcp`; the wrappers invoke Termux's absolute Node path because the upstream POSIX launcher uses `/usr/bin/env`, which is absent on this Termux host.
- Official local skills bundle: all five packaged skills installed at `$HOME/.codex/skills` and reported installed by `game-dev doctor`.
- Termux support: **partial experimental validation only**. Node 26.4.0, npm 11.19.1, SQLite catalog runtime, CLI capabilities, and the generic CPU sample workflow worked. However, `game-dev doctor` returns `healthy: false` because the required platform check reports `android arm64`. The upstream supported-platform list is macOS, Linux and Windows; Android/Termux is not listed.
- LudoMada capture adapter: **not configured**. There is no `.game-dev/adapter.json` in the main branch.
- LudoMada GPU/performance evidence: **not captured**. The successful sample is explicitly synthetic and cannot establish the real Android app's rendering, GPU execution, image quality, FPS or hardware timing.

## Paths

- CLI install: `$HOME/.local/share/GameDevelopmentStudio/cli`
- Termux-local workspace: `$HOME/.local/share/ludomada-game-development-studio/workspace`
- Sample project: `$HOME/.local/share/ludomada-game-development-studio/sample-project`
- Local Codex skills: `$HOME/.codex/skills`

Keep logs, captures, generated assets, packages and run bundles in the workspace outside the Git checkout. Do not copy synthetic files into LudoMada's production assets.

## Verified tests

1. Official release tarball verified against the published SHA-256 manifest.
2. npm installation completed successfully (102 packages); `game-dev --version` returns `1.5.0` through the Termux wrapper.
3. `game-dev capabilities --json`: `ok: true`.
4. `game-dev doctor --json`: node runtime, writable workspace, SQLite catalog runtime, CLI version, generic sample and all five skills pass; the required platform check fails on `android arm64`, so overall health remains false.
5. The packaged generic sample capture completed after raising only its disposable sample project's timeout from 2 to 30 seconds for the slower Termux host. Its run bundle verified with `capture.verify`; the adapter marks the result `synthetic-only`, `rendererClass: unknown`, and admits no GPU or hardware-performance evidence.

Details, run identifier and limits are recorded in `docs/game-development-studio-termux-verification-2026-10-12.md`.

## Adapter rule

Do not add `.game-dev/adapter.json` until a real LudoMada-specific capture command has been identified and verified. A valid future adapter must build or launch the actual Android app and capture its real rendering reproducibly. It must declare outputs, permissions and limitations. The generic sample adapter is not a substitute for the LudoMada app.

## Safety boundary

This tool owns asset inspection, controlled asset admission, visual evidence and bounded graphics optimization. It does not own or authorize changes to authoritative Ludo rules, turn/state reconciliation, server settlement, anti-cheat, authentication, MLG rewards, wallet, ledger, payment-provider live switches or database schema.

See `docs/game-development-studio-ludomada.md` and the verification report for the operating workflow, install boundary and acceptance criteria.
