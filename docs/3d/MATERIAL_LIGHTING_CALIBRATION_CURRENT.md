# Lane C current calibration contract

## Baseline

This contract is aligned to the shared integration branch at the time of the current handoff:

`feat/google-signin-real @ 3c10e2dea61c7f71be9c556fb30efee5753e1fe2`.

The active `LudoBoardView.kt` already contains the GLES3 PBR shader, clearcoat, secondary fill, shadow PCF, IBL reflection and post-process pass. Lane C therefore supplies calibrated parameters instead of creating another renderer.

## Authoring baseline vs runtime target

The 3D asset manifest describes the hydro-chrome die with metallic 0.95 / roughness 0.08. Those are the asset-authoring defaults.

Lane C runtime calibration intentionally tightens the final renderer response to:
- metallic 1.00
- roughness 0.055
- clearcoat 0.72
- clearcoat roughness 0.055

This is a renderer-facing calibration, not a claim that the source GLB itself must be re-authored.

## Required renderer deltas

| Parameter | Current renderer observed at handoff | Calibrated target |
|---|---:|---:|
| Hydro-chrome clearcoat | 0.22 | 0.72 |
| Hydro-chrome clearcoat roughness | 0.075 | 0.055 |
| Key/direct intensity | 1.05 | 1.08 |
| Secondary fill coefficient | 0.42 | 0.22 |
| Ambient/IBL contract | 0.72 | 0.82 |
| Shadow depth bias | 0.0018 | 0.00135 |
| Rim/edge contribution | implicit / not independently bound | 0.34 |
| Bloom post strength | 0.10 | 0.10 |
| SSAO post strength | 0.28 | 0.28 |

The strict audit intentionally reads the live `LudoBoardView.kt` and fails while any required runtime delta remains.

## Expected visual result

Hydro-plated chrome should show a broad readable environment reflection, a tight but non-crushed specular peak, and stronger grazing-angle edge separation. Lower fill should preserve contact/form shadows instead of flattening the scene.

Pawn lacquer should remain a glossy dielectric response with clearcoat rather than metallic coloration. Board materials should remain matte/satin enough to keep the pieces visually dominant.

## Device policy

Use render-quality tiers when implemented by Lane A:
- MOBILE_SAFE: reduced SSAO/bloom and lower reflection workload.
- BALANCED: 512px shadow target and moderate post-process.
- PREMIUM: higher-resolution shadows/reflections only when the device budget permits.

The current active renderer uses a 512px shadow map; the PREMIUM 1024px target is a future quality-tier option, not current runtime evidence.

Quality selection must remain visual-only and never enter authoritative gameplay state.

## Lane boundary

This contract does not modify `LudoBoardView.kt`. Lane A owns shader/uniform integration. Lane F owns device/Ramus proof.
