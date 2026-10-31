# LudoMada Premium 3D Asset Pipeline

## Current source scene

The authoritative gameplay visual source is Higgsfield 3D Jutsu project `083cab86-9817-4ddc-8d12-92d9212d008b`, revision 5.

The active gameplay GLB is:
- `android-app/app/src/main/assets/3d/ludomada-gameplay-rev5.glb`
- 867,800 bytes
- SHA-256 `bd648fd8b2a265e26220ce1eed3f8a7d60f8507345290cea6e19fba56f401e0f`

It contains the authored gameplay board and hero geometry:
- 52 path cells
- 24 finish-lane cells
- 4 home plinths
- 4 center wedges
- recessed board and deep table
- physical pawn geometry
- hydro-chrome die with 3D pips

The manifest records material revision 4 and the canonical `PremiumMaterialLighting.Calibration` contract. The older rev3 GLB remains only as a legacy asset; the runtime loader uses rev5 for both board and hero parts.

## Runtime status

`runtime_packaged: true`. The rev5 GLB is bundled in the Android app and its exact path is checked in debug APK builds and in the signed Release Gate APK.

`GlbRuntimeLoader.loadBoardParts()` loads the authored board geometry. `loadHeroParts()` loads the pawn and die components. The renderer continues to use the existing authoritative game state and deterministic accessibility controls; the GLB supplies visual geometry only.

The environment and real-baobab scenes are separate Home-preview assets. Their parsing and related texture uploads are deferred from normal gameplay.

## Material handoff

The active runtime die profile is sourced from `PremiumMaterialLighting.Calibration`:
- metallic: 1.00
- roughness: 0.055
- clear coat: 0.72
- clear-coat roughness: 0.055

The light rig uses calibrated key/fill/rim/ambient values, a 512px shadow map, and the restrained mobile post-process profile. See `MATERIAL_LIGHTING_CALIBRATION_CURRENT.md` and `PREMIUM_MATERIAL_LIGHTING_CURRENT.md` for the live values.

## Integration boundary

This pipeline is renderer/asset-only. It must not modify authoritative game state, turn logic, sortie, capture, arrival/exact finish, reconciliation, wallet/ledger, authentication, or payment providers.
