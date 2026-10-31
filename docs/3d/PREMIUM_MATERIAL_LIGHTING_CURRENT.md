# Premium material + lighting contract — current integration baseline

The native GLES3 renderer consumes the shared `PremiumMaterialLighting` profiles. This module owns material, lighting and post-process parameters only; it does not own gameplay state or input.

## Current material targets

| Asset | Runtime intent | Metallic | Roughness | Clear coat |
|---|---|---:|---:|---:|
| Pawn lacquer | Glossy colored dielectric lacquer | 0.06 | 0.23 | 0.22 |
| Hydro-chrome die | Silver hydro-plated chrome | 1.00 | 0.055 | 0.72 |
| Chrome tray | Polished dark/silver metal | 1.00 | 0.12 | 0.28 |
| Wood table | Warm finished wood | 0.04 | 0.42 | 0.05 |
| Board surface | Satin ivory | 0.02 | 0.47 | 0.03 |
| Gold trim | Polished gold | 0.88 | 0.20 | 0.12 |
| Pips | Dark inset metal | 0.72 | 0.16 | 0.12 |

Chrome roughness deliberately remains above zero. A perfect-mirror die is visually unstable on mobile and hides useful form cues.

## Lighting profile

`PremiumLightRig.mobilePremium()` uses:
- warm key at intensity 1.08
- cool secondary fill at intensity 0.22
- warm rim at intensity 0.34
- ambient/IBL intensity 0.82
- 512px shadow target and calibrated depth bias 0.00135

`PremiumLightRig.safeFallback()` reduces light workload and uses a 256px shadow target for constrained devices.

The optimization budget also defines a 384px BALANCED tier (96-particle cap); it remains a budget target until a separate runtime profile is implemented.

## Post-processing

`PremiumPostProcess.mobileHigh()` uses:
- exposure 1.00
- bloom 0.10
- SSAO 0.28
- saturation 1.025
- contrast 1.04
- warm bias 0.018
- vignette 0.035

`mobileSafe()` reduces exposure/bloom/SSAO and vignette for constrained devices.

## Reflection and material response

- Pawn lacquer keeps a glossy dielectric response with moderate clear coat.
- Chrome uses the calibrated high-metallic, low-roughness profile and environment response.
- Wood remains diffuse with a subtle coat.
- Board surfaces remain satin to preserve readable contrast.
- Gold retains a controlled specular response.

## Integration boundary

The profile values are shared renderer constants. They must remain visual-only: no changes to server-authoritative game state, turn rules, state reconciliation, touch callbacks, wallet, authentication or payment logic.
