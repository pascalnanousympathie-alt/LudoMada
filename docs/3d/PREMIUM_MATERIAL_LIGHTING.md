# Premium material + lighting contract

Lane C is renderer-facing only. It does not alter authoritative game state, turn rules, capture/sortie/arrival, wallet/ledger, authentication, or payment providers.

## Contract alignment

The current 3D asset contract uses meters and glTF 2.0. Its material targets are:

| Asset | Material | Target |
|---|---|---|
| Pawn | lacquer-pbr | low-metallic glossy lacquer |
| Die | hydro-chrome | metallic silver/chrome, metallic 0.95, roughness 0.08 |
| Table/board | layered | wood base, recessed board, gold frame |

The Kotlin helper mirrors those targets and the existing GLES3 PBR uniform model in LudoBoardView.kt.

## Runtime profiles

| Profile | Metallic | Roughness | Normal | Env |
|---|---:|---:|---:|---:|
| Wood | 0.04 | 0.42 | 0.24 | 0.62 |
| Board | 0.02 | 0.47 | 0.18 | 0.48 |
| Pawn lacquer | 0.06 | 0.23 | 0.14 | 0.78 |
| Hydro chrome | 0.95 | 0.08 | 0.06 | 1.00 |
| Chrome tray | 1.00 | 0.12 | 0.05 | 0.94 |
| Pip | 0.72 | 0.16 | 0.05 | 0.58 |
| Gold | 0.88 | 0.20 | 0.10 | 0.74 |

Chrome roughness remains non-zero to preserve readable mobile highlights instead of an unstable perfect-mirror response.

## Lighting target

mobilePremium uses a warm dominant key, cool/dim ambient contribution, 512px shadow map and moderate IBL. The intended response is visible form separation: bright edge highlights on lacquer, broad reflections on chrome, soft diffuse response on the board and controlled highlights on gold.

## Post-process target

The existing GLES3 post pass already supports SSAO, bloom and tone mapping. The high tier is intentionally restrained: bloom 0.10, SSAO 0.28, saturation 1.025, contrast 1.04, vignette 0.035.

The safe tier reduces SSAO/bloom and uses a 256px shadow target when frame time becomes unstable.

## Lane boundary

This branch owns the material/lighting contract only. Lane A can consume it after re-reading the current LudoBoardView.kt. Runtime integration must preserve the GLES2 fallback and existing input/game-state callbacks.
