# Premium material + lighting contract — current integration baseline

Lane C owns renderer-facing material, lighting and post-process parameters only.

## Current integration alignment

The active `feat/google-signin-real` renderer already contains:
- GLES3 PBR
- GGX-style direct specular
- procedural albedo
- normal mapping
- IBL cubemap reflections
- 512px PCF shadow map
- SSAO/bloom/tone-mapping post pass
- secondary cool fill light
- clear-coat response for chrome/lacquer materials

The Lane-C contract below is intentionally additive: it gives Lane A stable named profiles instead of embedding tuning constants inside gameplay rendering code.

## Material targets

| Asset | Target | Metallic | Roughness | Clear coat |
|---|---|---:|---:|---:|
| Pawn lacquer | glossy colored lacquer | 0.06 | 0.23 | 0.22 |
| Hydro-chrome die | silver hydro-plated chrome | 0.95 | 0.08 | 0.34 |
| Chrome tray | polished dark/silver metal | 1.00 | 0.12 | 0.28 |
| Wood table | warm finished wood | 0.04 | 0.42 | 0.05 |
| Board surface | satin ivory | 0.02 | 0.47 | 0.03 |
| Gold trim | polished gold | 0.88 | 0.20 | 0.12 |
| Pips | dark inset metal | 0.72 | 0.16 | 0.12 |

Chrome roughness deliberately remains above zero. A perfect-mirror die is visually unstable on a mobile device and hides form cues.

## Lighting targets

`mobilePremium()` provides:
- warm key
- cool secondary fill
- warm rim
- cool/dim ambient
- 512px shadow target
- moderate IBL

The intent is physical separation of surfaces: lacquer shows tight highlights, chrome shows broad environment reflections plus a sharp grazing response, wood stays diffuse, and gold retains controlled specular response.

## Post-processing

`mobileHigh()` keeps the existing post pass restrained:
- exposure 1.00
- bloom 0.10
- SSAO 0.28
- saturation 1.025
- contrast 1.04
- warm bias 0.018
- vignette 0.035

`mobileSafe()` reduces SSAO/bloom and drops the shadow target to 256px.

## Integration boundary

Lane A may map these profiles to existing uniforms and textures after re-reading the live `LudoBoardView.kt`.

Do not move game rules, state reconciliation, touch callbacks, wallet, authentication or payment logic into this module.

Do not remove the GLES2 fallback.

## Quality target

The visual bar is physically convincing realtime 3D: real geometry, perspective/depth, material response, environment reflection, contact shadows, believable highlight rolloff and controlled post-processing. Reference images remain visual targets; they are not used as gameplay background replacements.
