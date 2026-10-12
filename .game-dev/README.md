# LudoMada Game Development Studio boundary

This directory records the actual verified integration state for Game Development Studio. It distinguishes supported local tool execution from real-app evidence.

## Current state (verified 2026-10-12)

- Project role instructions: `.agents/skills/ludomada-game-development-studio/SKILL.md`.
- Upstream CLI: v1.5.0, installed from the official release tarball; the tarball SHA-256 matched the published manifest.
- CLI execution: the normal `$PREFIX/bin/game-dev` and `game-dev-mcp` wrappers now run the CLI inside the installed Debian 13 proot using the existing Linux ARM64 Node.js 24.19.0 from the Actions runner. The original Termux-native wrappers are preserved as `game-dev-termux` and `game-dev-mcp-termux`.
- `game-dev --version`: PASS, returns 1.5.0.
- `game-dev doctor --json` through the Linux-userland wrapper: PASS, `ok: true`, `healthy: true`; required `platform` is `linux arm64`, Node and SQLite checks pass, workspace is writable, all five skills are installed in `/root/.codex/skills`, and the packaged generic-sample check passes.
- Native Termux compatibility: still not upstream-supported. `game-dev-termux` remains available for direct Termux Node experiments, but its `doctor` platform check reports `android arm64`. Do not bypass or falsify that check; use the verified Debian/Linux wrapper.
- CLI skills: the five upstream skills are installed in both `$HOME/.codex/skills` and the Debian-userland `/root/.codex/skills`.
- Synthetic capture: sample run `run_1791825102917_1434308a3ac2463d90224ff056cf57c5` completed and `capture verify` returned `ok: true`. It remains explicitly synthetic-only and proves nothing about the live game GPU, FPS or visual quality.
- Asset inspection hotfix: upstream CLI v1.5.0's installed inspector only registered `KHRTextureBasisu` and failed on valid LudoMada GLBs requiring `KHR_lights_punctual`. The installed `dist/inspection/gltf.js` was locally patched to register `ALL_EXTENSIONS`; the original is preserved outside the repo at `$HOME/.local/share/ludomada-game-development-studio/patches/gltf.js.release-1.5.0.original`. Original SHA-256: `ce7359ea20d5371b3cc1af1f039d749dcd1d961c38a345c928b2f4d3414258a7`; patched file SHA-256: `6d5f376362f56ba4cf473df10b674a2eee08dd47fe5786366dc7cbcb6663c42c`. This is a local, reversible patch to the installed CLI package, not a source-tree change; reinstalling the CLI may replace it. Upstream issue: [#12](https://github.com/theisegoria/game-development-studio/issues/12).
- Real LudoMada capture adapter: **not configured**. The main branch has no `.game-dev/adapter.json`.
- Real GPU/performance evidence: **not captured**. The Android package `com.ludomadagascar` was absent on this host; ADB reports no connected devices; `/system/bin/screencap` from Termux produced a zero-byte output. The existing `StartupUiTest.kt` includes a screenshot after the offline-flow checks, but it requires a working install/instrumentation path.
- Android build: first attempt failed at AAPT2 because Gradle launched its Linux binary directly in Termux. A Termux-to-Debian AAPT2 wrapper has now passed a small resource-compile smoke test; see the dated report for the result of the full build retry.

## Paths

- CLI package: `$HOME/.local/share/GameDevelopmentStudio/cli`
- Default workspace: `$HOME/.local/share/ludomada-game-development-studio/workspace`
- Generic sample project: `$HOME/.local/share/ludomada-game-development-studio/sample-project`
- CLI patch backup/hashes: `$HOME/.local/share/ludomada-game-development-studio/patches`
- Termux wrapper backups: `$PREFIX/bin/game-dev-termux`, `$PREFIX/bin/game-dev-mcp-termux`
- Debian Linux Node runtime: `$HOME/actions-runner/externals/node24/bin/node`
- Local skills: `$HOME/.codex/skills` and Debian `/root/.codex/skills`

Keep output bundles, captures, caches, generated assets and receipts outside the Git source tree until reviewed.

## Responsibilities and safety

Game Development Studio owns technical asset inspection, license/provenance and reviewed vendoring, real visual-evidence analysis, and bounded graphics optimization. It does not own or authorize changes to authoritative Ludo rules, turn/state reconciliation, game settlement, wallet, ledger, MLG rewards, payment live switches, authentication, anti-cheat or database schema.

Do not admit a synthetic fixture as LudoMada evidence. Do not run paid providers without explicit per-invocation authorization. Do not normalize or replace GLBs automatically just to suppress warnings: inspected board assets report missing UVs/no attached base-colour textures for many primitives, which needs deliberate art-direction review.

See `docs/game-development-studio-ludomada.md` and `docs/game-development-studio-termux-verification-2026-10-12.md`.
