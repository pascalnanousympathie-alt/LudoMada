# Lane C current calibration contract

## Active baseline

This contract is aligned to the combined native 3D integration candidate:
`cbb913934901e107f591b788ca636c45b4a3ec8d`.

The active `LudoBoardView.kt` consumes the canonical `PremiumMaterialLighting.Calibration` values for the GLES3 PBR renderer. The legacy `LudoMaterialCalibration` API is a compatibility façade over the same constants.

## Authoring and runtime targets

The rev5 gameplay manifest records the hydro-chrome die authoring target as metallic 1.00, roughness 0.055, clear coat 0.72 and clear-coat roughness 0.055. These are also the current runtime profile values; they are not an outstanding re-authoring task.

The canonical runtime calibration is:

| Parameter | Active runtime value |
|---|---:|
| Hydro-chrome metallic | 1.00 |
| Hydro-chrome roughness | 0.055 |
| Hydro-chrome clear coat | 0.72 |
| Clear-coat roughness | 0.055 |
| Key/direct intensity | 1.08 |
| Secondary fill intensity | 0.22 |
| Rim intensity | 0.34 |
| Ambient/IBL intensity | 0.82 |
| Shadow depth bias | 0.00135 |
| Shadow map | 512px |
| Bloom | 0.10 |
| SSAO | 0.28 |

These values are defined in `PremiumMaterialLighting.Calibration` and consumed by the `hydroChrome()`, `mobilePremium()` and `mobileHigh()` profiles.

## Expected visual result

Hydro-plated chrome should show a broad readable environment reflection, a tight but not crushed specular peak, and grazing-angle edge separation. Lower fill should preserve contact/form shadows instead of flattening the scene.

Pawn lacquer remains a glossy dielectric response rather than metallic coloration. Board materials remain matte/satin so pieces remain visually dominant.

## Device policy

The optimization budget defines:
- `MOBILE_SAFE`: reduced SSAO/bloom and a 256px shadow target.
- `BALANCED`: 384px shadow target and moderate post-process (budget target; a dedicated profile is not yet wired).
- `PREMIUM`: 512px shadow target and the full current mobile post-process profile.

Quality selection must remain visual-only and never enter authoritative gameplay state.

## Lane boundary

This contract records the live renderer-facing values. It does not modify game rules, state reconciliation, touch callbacks, wallet, authentication or payment logic.
