# LudoMada Premium 3D Asset Pipeline

## Current source scene

The authoritative visual source is Higgsfield 3D Jutsu project `083cab86-9817-4ddc-8d12-92d9212d008b`, revision 3.

Revision 3 contains the calibrated real gameplay geometry and materials:
- 52 path cells
- 24 finish-lane cells
- 4 home plinths
- 4 center wedges
- recessed board and deep table
- physical pawn geometry
- hydro-chrome die with 3D pips

The exported GLB for revision 2 is 846,104 bytes (~0.81 MiB), below the 8 MiB hero-asset budget.

## Runtime status

`runtime_packaged=false` is intentional at this handoff. The binary GLB is not claimed to be embedded in the Android APK yet. The Android renderer currently recreates the gameplay board with native GLES2/GLES3 geometry.

Revision 3 is now the source-of-truth GLB handoff, including portable Principled PBR material values. Do not replace it with a screenshot/background image.

## Material handoff

Runtime Lane C/Lane A and source-asset calibration:
- hydro chrome: metallic 1.00
- hydro chrome: roughness 0.055
- clear coat: 0.72
- clear-coat roughness: 0.055
- key: 1.08
- fill: 0.22
- rim: 0.34
- IBL: 0.82

The rev3 source GLB now carries matching Principled material values for hydro-chrome, pawn lacquer, gold, board and path materials.

## Integration boundary

This pipeline is renderer/asset-only. It must not modify authoritative game state, turn logic, sortie, capture, arrival/exact finish, reconciliation, wallet/ledger, auth, or payment providers.