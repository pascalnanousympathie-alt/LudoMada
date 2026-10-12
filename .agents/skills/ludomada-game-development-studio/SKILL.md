---
name: ludomada-game-development-studio
description: LudoMada-specific workflow for 3D asset production, asset validation and vendoring, visual evidence, and bounded graphics-performance work. Do not use it to change game rules, backend state, or money/security systems.
---

# LudoMada Game Development Studio

Use this skill only for the graphical client and 3D asset pipeline. Read `docs/game-development-studio-ludomada.md` and `.game-dev/README.md` before making changes.

## Responsibilities

1. **Asset production and inspection** — inspect and validate existing GLB files; check materials, texture references, scale, orientation, mesh integrity, provenance, and license. Blender normalization and external-provider generation are optional, not assumed.
2. **Asset admission and vendoring** — require a known license, content hashes, provenance, validation results, and a reviewed destination before adding an asset. Run an admission plan first. A plan is not permission to write.
3. **Visual debugging** — diagnose the real LudoMada render from reproducible captures and logs. Keep screenshots, render attachments, telemetry, and run bundles outside the source tree. Label synthetic fixtures as synthetic; never present them as LudoMada or device evidence.
4. **Performance optimization** — compare baseline and candidate evidence captured on the same device with the same app build, scene, resolution, and settings. Make one bounded graphics change at a time and retain a rollback point.
5. **Workflow routing** — start with read-only inspection; report missing dependencies or evidence rather than inventing results. Use the upstream `game-dev` CLI only when it is actually installed and its version/platform are verified.

## First-use checks

Run only when `game-dev` is installed and available on the current host:

```sh
game-dev --version
game-dev capabilities --output-dir /absolute/path/to/ludomada-gds-workspace --json
game-dev doctor --output-dir /absolute/path/to/ludomada-gds-workspace --json
game-dev credentials status --output-dir /absolute/path/to/ludomada-gds-workspace --json
```

Use an absolute, project-specific output directory outside the Git working tree. Review and redact diagnostic output before sharing it. A credential status check may confirm presence only; never print, copy, or ask for credential values.

The official CLI requires Node.js 22.5+ and documents Windows, macOS, and Linux. Android/Termux is not yet validated for this project: verify it experimentally before relying on it. Do not describe it as installed or working until `game-dev --version`, `doctor`, and the appropriate capability checks succeed on that exact host.

## LudoMada-specific capture gate

No game-specific capture adapter is considered available merely because the CLI is installed. Before using scenarios against LudoMada, identify and test a real adapter that can reproducibly build/launch the Android app and capture its actual rendering. Confirm its commands, parameters, output roster, and device/GPU permissions. If that adapter is absent, limit work to static asset inspection and report that end-to-end render evidence is blocked. Do not install a generic sample adapter as if it were a LudoMada adapter.

## Hard boundaries

- Never change authoritative Ludo rules, turn/state reconciliation, server settlement, wallet, ledger, MLG rewards, payment-provider switches, auth, anti-cheat, or database schema as part of a graphics task.
- Prefer narrowly scoped renderer, shader, material, texture, mesh, and cosmetic changes under the Android client. If a requested change crosses a protected boundary, stop and propose a separate review.
- Do not replace the live 3D scene with a static image, flat Canvas mock, painterly stand-in, or fake Home values. Preserve real interactive gameplay and the existing state authority.
- Do not run paid Tripo/Leonardo/provider work or write/normalize/package/admit assets without the exact invocation and cost/write authorization required by the upstream tool and the user's approval.
- Never put secrets in source, command arguments, requests, logs, captures, or reports.
- Keep each change on a dedicated branch and submit a reviewable PR. Do not merge automatically. Include test/capture evidence and explicitly state what was not tested.
