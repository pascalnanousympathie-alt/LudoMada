# Game Development Studio for LudoMada

## Integration status

The project-specific instructions are in `.agents/skills/ludomada-game-development-studio/SKILL.md`. The upstream Game Development Studio CLI v1.5.0 is installed from a release tarball whose SHA-256 matched the official manifest. The default `$PREFIX/bin/game-dev` and `game-dev-mcp` wrappers now run it inside the installed Debian 13 proot using Linux ARM64 Node.js 24.19.0 from the Actions runner. The earlier Termux-native wrappers are preserved as `game-dev-termux` and `game-dev-mcp-termux`.

**The supported Linux-userland path is verified.** Through the default wrapper, `game-dev doctor --json` returns `ok: true`, `healthy: true`, and `platform: linux arm64`; Node 24.19.0, SQLite, workspace, generic sample and all five skills pass. The unwrapped native Termux path remains outside the upstream supported-platform list and reports `android arm64`, so that check has not been falsified or disabled. The generic capture is synthetic-only; no LudoMada scene, live GPU, hardware timing or visual-quality evidence is implied.

The five local skills are installed both under `$HOME/.codex/skills` and the Debian userland's `/root/.codex/skills`. This is separate from installation of the upstream ChatGPT/marketplace plugin, which has not been installed. The complete evidence and limitations are in `docs/game-development-studio-termux-verification-2026-10-12.md`.

## Where it belongs

- **Project instruction / role:** `.agents/skills/ludomada-game-development-studio/SKILL.md`
- **Project integration notes:** `.game-dev/README.md`
- **Operating guide:** this file
- **Verification record:** `docs/game-development-studio-termux-verification-2026-10-12.md`
- **Local CLI:** `$HOME/.local/share/GameDevelopmentStudio/cli`
- **Local evidence workspace:** `$HOME/.local/share/ludomada-game-development-studio/workspace`
- **Disposable generic sample:** `$HOME/.local/share/ludomada-game-development-studio/sample-project`
- **Local Codex skills:** `$HOME/.codex/skills`
- **Game-specific capture adapter:** not configured. The repository does not currently contain `.game-dev/adapter.json`.

Keep generated files, caches, logs, capture bundles, provider receipts and temporary normalized assets in the workspace outside the Git checkout until reviewed.

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

It does not replace the GLES3/PBR renderer or the actual interactive game. Static screenshots, synthetic demo captures, CPU previews, or marketing artwork are not proof that the game renders correctly on the target Android device.

## Activation sequence

1. **Check the host.** Node.js must be 22.5 or newer. Follow the upstream [install guide](https://github.com/theisegoria/game-development-studio/blob/main/docs/install.md), which documents Windows, macOS and Linux. On this Android host, the default wrapper enters the installed Debian proot and uses Linux Node 24.19.0; this route passes `doctor`. The direct Termux-native path remains unsupported upstream.
2. **Verify before using.** Run `game-dev --version`, `game-dev capabilities --output-dir /absolute/path/to/ludomada-gds-workspace --json`, and `game-dev doctor --output-dir /absolute/path/to/ludomada-gds-workspace --json`. The default wrapper should report `linux arm64` and healthy=true. Review output for errors and redact it before sharing.
3. **Begin read-only.** Inspect and validate existing assets. Do not normalize, generate, package, admit, or overwrite assets during initial diagnosis.
4. **Prove the LudoMada adapter.** Discover the real command to build/launch the Android client and capture its actual rendered scene. Validate allowed commands, parameters, output files and hashes, permissions, device identity and repeatability. Until this gate passes, do not claim automated LudoMada captures or hardware profiling.
5. **Establish a baseline.** Capture the same known scene on a specified device/build/settings. Keep actual device data separate from synthetic fixtures and CPU-only previews.
6. **Make one controlled change.** Propose the smallest graphics-only patch, review its diff, build/test it, capture a comparable candidate run, and compare against the baseline. If evidence is missing or incomparable, report the blocker instead of guessing.
7. **Review and admit.** A human reviews visual quality, performance evidence, source/license information and the final diff. Submit the change through a separate PR; do not merge automatically.

## Installation surfaces

- **Repository-local:** project role instructions and integration notes are tracked in the repository.
- **Official skills bundle:** five upstream skills installed into local `$HOME/.codex/skills`; they are not the same as the separate ChatGPT/marketplace plugin.
- **Local CLI:** upstream v1.5.0 installed in a dedicated prefix after SHA-256 verification. Termux wrappers bypass the incompatible `/usr/bin/env` launcher; see the dated report for the exact paths and results.
- **Termux/Android:** local CPU/synthetic workflow partially works, but `doctor` remains unhealthy because Android/Termux is outside the upstream supported-platform list. A real LudoMada capture adapter is still missing.

## Acceptance checklist

- [x] CLI version and checksum-verified release tarball recorded.
- [x] Dedicated workspace outside the repository created.
- [x] Five local skills installed in Termux and Debian-userland skill roots.
- [x] Generic synthetic capture bundle created and verified; evidence limitations retained.
- [x] Official platform gate passes in the Debian/Linux-userland wrapper with Node 24.19.0; native Termux remains unsupported upstream.
- [ ] Existing LudoMada GLB/texture assets inspected with no writes or provider calls.
- [ ] A real LudoMada capture adapter is defined, reviewed, and repeatable.
- [ ] Baseline capture includes app build, device, graphics settings, timestamps, output roster and hashes.
- [ ] A graphics-only candidate is compared to baseline and build/tests pass.
- [ ] License/provenance and generated outputs are reviewed before admission.
- [x] Protected gameplay, economy, authentication, and security boundaries remain untouched by this integration.
