# LudoMada Game Development Studio boundary

This directory records LudoMada-specific setup status for the upstream Game Development Studio tools.

## Current state

- Project-specific agent skill: present at `.agents/skills/ludomada-game-development-studio/SKILL.md`
- Operating guide: present at `docs/game-development-studio-ludomada.md`
- Upstream `game-dev` CLI on Termux: **not verified**
- Upstream plugin installed into a local Codex/ChatGPT profile: **not verified**
- LudoMada capture adapter: **not configured**
- GPU/performance evidence from the Android game: **not captured by this integration**

These states are intentionally explicit so automation does not confuse documentation with an installed executable or a tested graphics pipeline.

## Workspace rule

Use an absolute workspace outside the checkout, such as:

```sh
mkdir -p "$HOME/.local/share/ludomada-game-development-studio"
```

Pass that absolute path as the CLI's `--output-dir` when appropriate. Do not put job state, provider receipts, logs, capture bundles, caches, generated/normalized files, or large test artifacts into the Git source tree by default. Review any candidate asset and its license/provenance before copying it into the repository.

## Adapter rule

Do not add `.game-dev/adapter.json` until a real LudoMada-specific capture command has been identified and verified. A valid future adapter must not silently use synthetic samples in place of the Android app, must declare all outputs and permissions, and must be tested on the intended host/device. The absence of an adapter blocks automated end-to-end render capture, not static inspection of existing assets.

See `docs/game-development-studio-ludomada.md` for the role map, installation boundary, safeguards and acceptance checklist.
