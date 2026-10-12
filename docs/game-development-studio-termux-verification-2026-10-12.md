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


---

## Follow-up resolution attempt (later on 2026-10-12)

### Linux-userland compatibility gate: PASS

The direct Termux path reports `android arm64`, which remains outside the upstream platform list. Rather than patching that result, the active `$PREFIX/bin/game-dev` and `game-dev-mcp` wrappers were redirected into the existing Debian 13 proot using the existing Actions-runner Linux ARM64 Node binary:

- Node executable: `$HOME/actions-runner/externals/node24/bin/node`
- Runtime inside Debian: `linux arm64 v24.19.0`
- CLI version through `game-dev --version`: `1.5.0`
- `game-dev doctor --json`: `ok: true`, `healthy: true`, `platform: linux arm64`
- Required Node runtime and `node:sqlite` checks: PASS
- Workspace and generic-sample checks: PASS
- All five skills: PASS in the Debian userland `/root/.codex/skills` root
- Native- Termux wrappers are preserved as `$PREFIX/bin/game-dev-termux` and `$PREFIX/bin/game-dev-mcp-termux`; they are fallbacks, not the recommended route.

The outer device is still Android/Termux. A green doctor result applies to the supported Linux userland running under proot; it is not proof that upstream officially supports native Android or that a GPU driver has been validated.

### Synthetic capture: PASS again

A new run was created through the default Linux-userland wrapper and verified:

- Run ID: `run_1791825102917_1434308a3ac2463d90224ff056cf57c5`
- Run-manifest SHA-256: `cad6b77053f6c72b9974cd3522949a7ef9433da3a591238c638ab37fab51308b`
- Status: `completed`, exit code 0
- `game-dev capture verify`: `ok: true`

As with the earlier sample, this is synthetic-only evidence. Its raster is a fixture, not a screenshot of the LudoMada scene. It does not establish GPU completion, hardware timing, FPS, visual quality, or successful on-device rendering.

### GLB inspection: local compatibility hotfix and upstream issue

The installed v1.5.0 inspector originally failed on LudoMada GLBs because it registered only `KHRTextureBasisu`, while some valid model files declare the required `KHR_lights_punctual` extension and also use `KHR_materials_clearcoat`. A local, reversible hotfix in the installed CLI's `dist/inspection/gltf.js` registers `ALL_EXTENSIONS` from the already bundled glTF-Transform extension package. The original installed file is preserved outside the repository at:

```
$HOME/.local/share/ludomada-game-development-studio/patches/gltf.js.release-1.5.0.original
```

- Original SHA-256: `ce7359ea20d5371b3cc1af1f039d749dcd1d961c38a345c928b2f4d3414258a7`
- Patched SHA-256: `6d5f376362f56ba4cf473df10b674a2eee08dd47fe5786366dc7cbcb6663c42c`
- Upstream tracking issue: [theisegoria/game-development-studio #12](https://github.com/theisegoria/game-development-studio/issues/12)

The hotfix is not a committed project or upstream code change; reinstalling the upstream CLI may replace it. Five real project GLBs were parsed with the bundled GLTF-Transform extensions; `game-dev asset inspect` also returned `ok: true` for the gameplay market GLB after the hotfix. Current reports show missing UVs/no base-colour textures on many primitives. Those warnings are retained; no asset was normalized or overwritten to hide them.

Observed asset metrics:

| Asset | Meshes | Primitives | Triangles | Reported warning |
|---|---:|---:|---:|---|
| `ludomada-premium-3d-rev3.glb` | 112 | 112 | 33,654 | 4 primitives missing UV; no base-colour texture reported |
| `ludomada-environment-final.glb` | 56 | 56 | 13,086 | 34 primitives missing UV; no base-colour texture reported |
| `ludomada-gameplay-rev5.glb` | 114 | 114 | 53,214 | 4 primitives missing UV; no base-colour texture reported |
| `ludomada-gameplay-market-v6.glb` | 153 | 185 | 355,650 | 4 primitives missing UV; no base-colour texture reported |
| `environment/baobab-real.glb` | 2 | 2 | 512 | 1 primitive missing UV; 256×256 texture below the 1024-pixel recommendation |

These are the inspector's technical findings; they are not a human artistic-quality verdict. Keep source GLBs unchanged until the graphics owner reviews the affected materials/UVs and agrees to a specific asset change.

### Real-app capture and APK build: still gated

The repository does have `android-app/app/src/androidTest/java/com/ludomadagascar/StartupUiTest.kt`, which traverses Home → offline game and requests a screenshot at `/sdcard/Download/ludomada-startup-offline.png`. But the Android application is not installed on this host (`pm path com.ludomadagascar` returned no package), and `adb devices -l` listed no connected device. Calling `am start -n com.ludomadagascar/.MainActivity` returned Android error type 3 (activity absent). A direct Termux call to `/system/bin/screencap` produced a zero-byte file, so it is not admitted as screenshot evidence.

The first local `assembleDebug` retry failed because Gradle attempted to launch a Linux-host AAPT2 binary directly under Termux; the error was `syntax error: unexpected '('`. A custom wrapper was built in the external workspace at `$HOME/.local/share/ludomada-game-development-studio/aapt2-wrapper/aapt2`. It invokes the ARM64 AAPT2 binary through Debian proot and retains the caller's working directory. Its isolated resource compilation smoke test passed. The full Android `assembleDebug` retry using this wrapper is not marked PASS in this report until Gradle exits successfully and the output APK's timestamp/hash are checked.

No actual game screenshot, GPU trace, FPS measurement, or hardware performance conclusion has been created by these tests. A real capture adapter remains unconfigured until the Android app can be built, installed/launched with an approved method, and captured repeatably. No game rules, wallet, ledger, MLG, payment, authentication or anti-cheat code was changed.


### Codex MCP registration: PASS

The MCP server was installed as a local Debian-userland command at `/usr/local/bin/game-dev-mcp`, which invokes the same checksum-verified CLI's `dist/mcp/server.js` through Linux Node 24.19.0. An MCP stdio `initialize` request returned protocol `2025-03-26`, server name `game-development-studio`, version `1.5.0`. The CLI-generated Codex snippet was added to `/root/.codex/config.toml` after making a backup at `/root/.codex/config.toml.before-game-dev-20261012`. `codex mcp list` reports `game_dev` as enabled. The server is local stdio and does not use an OAuth flow; Codex labels its auth mode as unsupported because it is not a web-auth server.

The configured server sets `ASSET_OUTPUT_DIR` to the external LudoMada workspace. Paid provider tools remain disabled, and scenario execution/project writes/GPU/performance authorities have not been enabled. That restriction is intentional while the real LudoMada capture adapter and its execution commands remain unverified.
