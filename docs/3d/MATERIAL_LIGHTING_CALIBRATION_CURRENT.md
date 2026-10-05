# Lane C current calibration contract

## Baseline

This contract targets the live integration branch:

`feat/google-signin-real` @ `b42f2c9a43b869eb3c61233e22663b37f45cf68b`.

The existing `LudoBoardView.kt` already contains the GLES3 PBR shader, clearcoat, secondary fill, shadow PCF, IBL reflection and post-process pass. Lane C therefore supplies calibrated parameters instead of creating another material catalog.

## Required renderer deltas

| Parameter | Current renderer observed | Calibrated target |
|---|---:|---:|
| Hydro-chrome clearcoat | 0.22 | 0.72 |
| Hydro-chrome clearcoat roughness | 0.075 | 0.055 |
| Key/direct intensity | 1.05 | 1.08 |
| Secondary fill coefficient | 0.42 | 0.22 |
| Ambient/IBL contract | 0.72 | 0.82 |
| Shadow depth bias | 0.0018 | 0.00135 |
| Rim/edge contribution | implicit/none as separate control | 0.34 |
| Bloom post strength | 0.10 | 0.10 |
| SSAO post strength | 0.28 | 0.28 |

The first six values are the direct visual correction targets. Rim is a separate contract so Lane A can add the contribution without duplicating material definitions.

## Expected visual result

Hydro-plated chrome should show a broad readable environment reflection, a tight but non-crushed specular peak, and stronger edge separation. The lower fill preserves contact/form shadows instead of flattening the scene.

Pawn lacquer should retain a glossy dielectric response with clearcoat rather than metallic coloration. Board materials should remain matte/satin enough to keep the pieces visually dominant.

## Device policy

Use the shared render-quality tiers when available:

- MOBILE_SAFE: 256 shadow, reduced SSAO/bloom, lower normal/reflection scale.
- BALANCED: 512 shadow, moderate SSAO/bloom.
- PREMIUM: 1024 shadow, stronger reflections and PCF radius.

Quality selection must remain visual-only and never enter authoritative gameplay state.

## Lane boundary

This contract does not modify `LudoBoardView.kt`. Lane A owns shader/uniform integration. Lane F owns device/Ramus proof.
