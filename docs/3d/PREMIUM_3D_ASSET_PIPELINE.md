# LudoMada Premium 3D Asset Pipeline

## Current source scene

The authoritative visual source is Higgsfield 3D Jutsu project `083cab86-9817-4ddc-8d12-92d9212d008b`, revision 2.

Revision 2 contains real gameplay geometry:
- 52 path cells
- 24 finish-lane cells
- 4 home plinths
- 4 center wedges
- recessed board and deep table
- physical pawn geometry
- hydro-chrome die with 3D pips

The exported GLB for revision 2 is 846,100 bytes (~0.81 MiB), below the 8 MiB hero-asset budget.

## Runtime status

`runtime_packaged=false` is intentional at this handoff. The binary GLB is not claimed to be embedded in the Android APK yet. The Android renderer currently recreates the gameplay board with native GLES2/GLES3 geometry.

The GLB is the source-of-truth asset handoff for the next runtime asset-import lane. Do not replace it with a screenshot/background image.

## Material handoff

Runtime Lane C/Lane A calibration:
- hydro chrome: metallic 1.00
- hydro chrome: roughness 0.055
- clear coat: 0.72
- clear-coat roughness: 0.055
- key: 1.08
- fill: 0.22
- rim: 0.34
- IBL: 0.82

Those runtime values are renderer calibration and may differ from the source authoring defaults.

## Integration boundary

This pipeline is renderer/asset-only. It must not modify authoritative game state, turn logic, sortie, capture, arrival/exact finish, reconciliation, wallet/ledger, auth, or payment providers.
