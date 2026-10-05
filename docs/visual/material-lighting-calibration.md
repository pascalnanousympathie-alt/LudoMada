# LudoMada Premium Material / Lighting Calibration

Lane C reference for the current GLES3 renderer.

## Material targets

| Renderer slot | Intended use | Metallic | Roughness | AO | Normal | Clearcoat |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | dark wood / trim | 0.02 | 0.38 | 0.94 | 0.20 | 0.12 |
| 2 | board lacquer / colored surfaces | 0.08 | 0.31 | 0.96 | 0.16 | 0.28 |
| 3 | ceramic / light neutral | 0.04 | 0.42 | 0.98 | 0.10 | 0.16 |
| 4 | chrome / hydro-plated die | 1.00 | 0.055 | 1.00 | 0.045 | 0.72 |

## Current renderer discrepancy to reconcile

The existing GLES3 PbrShader.draw() path currently assigns approximately:
- slot 4 clearcoat: 0.22
- slot 3 clearcoat: 0.34
- slot 2 clearcoat: 0.16

Lane C recommends consuming the centralized presets instead of maintaining a second table of hardcoded values. The most important visual correction is the slot-4 chrome clearcoat target (0.72) together with the low roughness (0.055).

## Lighting targets

### Premium
- IBL intensity: 0.82
- key intensity: 1.08
- fill intensity: 0.22
- rim intensity: 0.34
- shadow bias: 0.00135
- shadow normal bias: 0.0065
- bloom threshold: 0.72
- bloom intensity: 0.16
- SSAO strength: 0.84

### Balanced mobile
- IBL intensity: 0.74
- key intensity: 1.00
- fill intensity: 0.18
- rim intensity: 0.26
- shadow bias: 0.0017
- shadow normal bias: 0.0085
- bloom threshold: 0.76
- bloom intensity: 0.12
- SSAO strength: 0.68

## Color-space contract

Color/albedo values authored as conventional sRGB values must be decoded to linear before BRDF evaluation. The existing post-process stage should remain responsible for tone mapping/output transfer.

Do not apply sRGB conversion to:
- metallic
- roughness
- AO
- normal-map vectors

## Integration order

1. Lane A maps the current renderer material IDs to LudoMaterialLighting.
2. Preserve the existing GLES2 path.
3. Compare chrome highlights, contact shadows, pawn lacquer response and board depth on Ramus.
4. Lane F captures evidence only after visual regressions are cleared.

No gameplay state, turn rules, wallet, authentication or payment behavior belongs in this calibration.