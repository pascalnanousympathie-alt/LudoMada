# Game Development Studio for LudoMada

## Integration status

This repository now has project-local operating instructions at `.agents/skills/ludomada-game-development-studio/SKILL.md`. Those instructions route eligible tasks and enforce LudoMada's existing safety boundaries.

This does **not** mean the upstream `game-dev` executable or the upstream ChatGPT/Codex plugin is installed on the current Android/Termux host. The CLI and skills plugin are separate distributions. No CLI execution, asset generation, Blender operation, GPU capture, or provider request has been claimed as completed by this repository change.

## Where it belongs

- **Project instruction / role:** `.agents/skills/ludomada-game-development-studio/SKILL.md`
- **Project integration notes:** `.game-dev/README.md`
- **This operating guide:** `docs/game-development-studio-ludomada.md`
- **Local evidence workspace:** an absolute directory outside the Git checkout, for example `$HOME/.local/share/ludomada-game-development-studio`. Keep generated files, cache, logs, capture bundles, provider receipts, and temporary normalized assets there until individually reviewed.
- **Game-specific capture adapter:** intentionally not installed yet. The CLI must be able to drive a real, reproducible LudoMada Android build/launch/capture workflow before an adapter can be trusted.

The local skill is a project-specific routing/policy layer, not a replacement for the upstream plugin or CLI.

## Owned responsibilities

| Workstream | Owned by Game Development Studio | Output expected |
|---|---|---|
| 3D asset inspection | Inspect/validate GLB, textures, materials, transforms, mesh integrity, and source metadata | Machine-readable inspection/validation report |
| Asset admission | Verify license, hashes, provenance, package integrity and destination before vendoring | Reviewed admission plan and then an explicitly authorized receipt |
| Visual diagnosis | Compare actual, repeatable captures and logs; maintain a clear evidence roster | Capture bundle, visual comparison, limitations |
| Graphics performance | Compare like-for-like measurements and make bounded renderer changes | Baseline/candidate metrics and rollback path |
| Provider-assisted content | Optional only; use the user's own configured account and explicit per-invocation authorization | Provider receipt and reviewable asset package |

## Explicitly out of scope

The Studio does not own or authorize changes to authoritative Ludo rules, turn and state reconciliation, server-side outcomes, anti-cheat, authentication, MLG rewards, wallet, ledger, payment-provider live switches, database migrations, or regulatory controls. Those changes require their existing, separate review and test workflows.

It does not replace the GLES3/PBR renderer or the actual interactive game. Static screenshots, synthetic demo captures, CPU previews, or marketing artwork are not proof that the game renders correctly on the Honor 8X or another device.

## Activation sequence

1. **Check the host.** Verify Node.js is 22.5 or newer, then follow the upstream install and checksum instructions: [Game Development Studio install guide](https://github.com/theisegoria/game-development-studio/blob/main/docs/install.md). The upstream documents Windows, macOS, and Linux. Termux/Android has not been validated here; do not assume host compatibility.
2. **Verify before using.** On the chosen host, run `game-dev --version`, `game-dev doctor --output-dir /absolute/path/to/ludomada-gds-workspace --json`, and `game-dev capabilities --output-dir /absolute/path/to/ludomada-gds-workspace --json`. Review output for errors and redact it before sharing.
3. **Begin read-only.** Inspect and validate existing assets. Do not normalize, generate, package, admit, or overwrite assets during initial diagnosis.
4. **Prove the LudoMada adapter.** Discover a real way to build/launch the Android client and capture the running 3D scene. Validate command allowlists, parameters, output files and hashes, permissions, device identity, and repeatability. Until this gate passes, do not claim Game Development Studio can automate LudoMada screenshots or hardware profiling.
5. **Establish a baseline.** Capture the same known scene on a specified device/build/settings. Keep actual device data separate from synthetic fixtures and CPU-only previews.
6. **Make one controlled change.** Propose the smallest graphics-only patch, review its diff, build/test it, capture a comparable candidate run, and compare against baseline. If evidence is missing or incomparable, report the blocker instead of guessing.
7. **Review and admit.** A human reviews visual quality, performance evidence, source/license information and the final diff. Submit the change through a separate PR; do not merge automatically.

## Installation surfaces

- **Repository-local:** the LudoMada skill and operating notes have been added in this branch.
- **Official skills plugin:** separate optional installation for a supported ChatGPT/Codex plugin environment; it does not install the CLI. See the upstream [skills repository](https://github.com/theisegoria/game-development-studio-skills).
- **Local CLI:** separate installation following the upstream install guide. The public npm registry package is not published; use the official release/source procedure rather than an unverified `npm install -g` command.
- **Termux/Android:** pending platform compatibility check. No claim of installation or operation is made until commands pass on that actual host.

## Acceptance checklist

- [ ] CLI version and host compatibility confirmed on the intended machine.
- [ ] `doctor` and `capabilities` pass using an absolute workspace outside the repo.
- [ ] Existing assets can be inspected without provider calls or writes.
- [ ] A real LudoMada capture adapter is defined, reviewed, and repeatable.
- [ ] Baseline capture includes its app build, device, graphics settings, timestamps, output roster and hashes.
- [ ] A graphics-only candidate is compared to baseline and build/tests pass.
- [ ] License/provenance and generated outputs are reviewed before admission.
- [ ] Protected gameplay, economy, authentication, and security boundaries remain untouched.
