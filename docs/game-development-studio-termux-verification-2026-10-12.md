# Game Development Studio — Termux verification (2026-10-12)

## Verdict

**Partially usable for local CPU workflows; not officially platform-supported on Termux.** The upstream CLI release 1.5.0 was downloaded and installed on the connected Termux/Android ARM64 host. Its direct Node entrypoint and the local generic synthetic capture workflow work, but the supported-platform gate in `game-dev doctor` intentionally reports `android arm64` as a required failure. Do not treat this as full compatibility or as proof of rendering performance on LudoMada.

## Host and install

- Host platform/architecture from Node: `android arm64`
- Node.js: `v26.4.0` (meets upstream minimum `>=22.5`)
- npm: `11.19.1`
- CLI: `1.5.0`
- Official release tarball: `theisegoria-game-development-studio-1.5.0.tgz`
- Official SHA-256: `165c3ab5db7fea3178f016a2e9b9bcbb1dcaf803de7ff38dbfbb2ee19ddb21fa`
- Observed SHA-256: the same value as the published `SHA256SUMS.txt`
- npm installation: succeeded; 102 packages added; installed under `$HOME/.local/share/GameDevelopmentStudio/cli`
- Provider calls: none. Tripo and Leonardo credentials report not configured.

The upstream launcher under the npm prefix uses `#!/usr/bin/env node`, which fails on this Termux host because `/usr/bin/env` is absent. Two Termux-local wrappers were therefore created at `$PREFIX/bin/game-dev` and `$PREFIX/bin/game-dev-mcp`; they call the Termux Node executable by absolute path and do not modify the checksum-verified package. The wrappers default `ASSET_OUTPUT_DIR` to the external LudoMada workspace when it is unset or inherited from Desktop Commander's npm-cache directory. `game-dev --version` was verified to print `1.5.0` with exit code 0.

## Local skills

The CLI's packaged skill bundle v1.5.0 was installed using a dry-run followed by explicit confirmation. All five are present under `$HOME/.codex/skills` and are reported as installed by the subsequent `doctor` check:

- `game-development-studio`
- `game-asset-production`
- `game-asset-vendoring`
- `game-visual-debugging`
- `game-performance-optimization`

This is the local Codex skills bundle, not the separate ChatGPT/marketplace plugin installation.

## Checks executed

| Check | Result | What it proves |
|---|---|---|
| Release SHA-256 | PASS | Tarball bytes match the published manifest entry |
| npm install | PASS | The local package and dependencies install on this host |
| `game-dev --version` | PASS | Termux wrapper launches CLI v1.5.0 |
| `game-dev capabilities --json` | PASS (`ok: true`) | CLI can enumerate its local operation contract |
| `game-dev doctor --json` | FAIL overall | Required platform check says `android arm64`; Node runtime, workspace, SQLite catalog, CLI helper version, generic sample and five skills pass |
| Generic sample project | PASS | Sample project and its adapter manifest can be created outside the repository |
| Synthetic capture | PASS after test-only timeout adjustment | Sample child process produced a capture manifest and PNG; sealed artifact roster was hashed |
| `capture verify` | PASS (`ok: true`) | Run manifest and recorded artifact roster verify |

### Synthetic capture details

- Sample project: `$HOME/.local/share/ludomada-game-development-studio/sample-project`
- Workspace: `$HOME/.local/share/ludomada-game-development-studio/workspace`
- Successful run: `run_1791822217943_9ef3da026ee64566856b3ab31b1050ba`
- Run-manifest SHA-256: `227fdc799ae64a52e0174236a029e768b500264da35c8c424beaed6ace15482e`
- The upstream sample adapter originally has a 2-second timeout; the disposable sample's timeout was raised to 30 seconds because Node startup/child execution exceeded 2 seconds on this Termux host.
- The successful run explicitly reports `evidence: synthetic-only`, `rendererClass: unknown`, no GPU-completion proof, no hardware-performance admission, and no human visual review.

The first test attempt inherited an `ASSET_OUTPUT_DIR` pointing inside Desktop Commander's npm cache and timed out. That exact failed run folder was removed after checking its run ID. The wrappers were adjusted to use the external workspace for an unset or npm-cache-derived `ASSET_OUTPUT_DIR`. The final successful run and its bundle remain in the external workspace.

## Still not verified

1. Official platform compatibility: `doctor` remains unhealthy because Android/Termux is not in the upstream supported-platform list (macOS, Linux, Windows).
2. Real LudoMada capture: fetching `pascalnanousympathie-alt/LudoMada` on `main` confirms `.game-dev/adapter.json` is absent.
3. Actual Android app launch/build/capture adapter, repeatability, and real render-image capture.
4. GLES/GPU identity, frame pacing, FPS, hardware timing, and any performance claim.
5. Blender, Basis Universal, CoACD, USDZ archiver, provider setup, and paid provider operations.

## Next safe step

Identify the exact local Android build/launch and screenshot mechanism for LudoMada, then write and review a small project-owned adapter that calls only those known commands. Test it against the actual app with a disposable run directory and closed artifact roster. Do not install a generic sample adapter as if it were a LudoMada adapter, and do not grant GPU/performance authority until the adapter's evidence contract is reviewed.

Keep all Game Development Studio run bundles outside the Git checkout. The successful generic run proves the harness's synthetic contract only, not LudoMada's live scene.
