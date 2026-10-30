# Lane E — mobile 3D optimization contract

Lane E protects visual quality while controlling APK size, memory and GPU cost on Android.

## Current asset state

The live manifest at the combined 3D integration candidate records:
- Higgsfield 3D Jutsu project `083cab86-9817-4ddc-8d12-92d9212d008b`
- gameplay source revision 5
- gameplay GLB size: 867,800 bytes
- SHA-256: `bd648fd8b2a265e26220ce1eed3f8a7d60f8507345290cea6e19fba56f401e0f`
- `runtime_packaged: true`

The active runtime asset is `3d/ludomada-gameplay-rev5.glb`. `GlbRuntimeLoader` loads the authored board and hero parts from this file. The older `ludomada-premium-3d-rev3.glb` remains in the asset directory as a legacy reference; it is not the active gameplay asset.

The environment and baobab GLBs are separate assets. Their parsing and 2K texture uploads are deferred to the Home preview path rather than ordinary gameplay startup.

## Budget

- Hero gameplay GLB: <= 8 MiB.
- Optional prop GLB target: <= 2 MiB.
- Gameplay texture target: <= 2048 px where detail is needed.
- KTX2/Basis is preferred for texture delivery.
- Meshopt or Draco is preferred for geometry compression.
- Large repeated meshes should be shared/instanced rather than duplicated.

## Runtime tiers

The optimization contract defines:
- `MOBILE_SAFE`: 256px shadow target, reduced post effects, 48-particle cap.
- `BALANCED`: 384px shadow target, moderate post effects, 96-particle cap.
- `PREMIUM`: 512px shadow target, full current post stack, 128-particle cap.

The current renderer exposes the safe and premium profiles. The balanced tier remains a budget target until a dedicated runtime profile is wired.

## Validation semantics

- `scripts/check-3d-assets.mjs` checks the manifest, rev5 source metadata, material calibration, expected hero nodes, packaged source asset size and SHA-256.
- `scripts/check-3d-optimization.mjs` enforces source metadata equality between manifest and budget, the hero size budget, bundled top-level GLB sizes and tier ordering.
- `scripts/check-apk-3d-asset.mjs` checks that the exact rev5 asset path is present in the APK.
- The Release Gate repeats these checks on the signed release artifact.

A passing optimization contract does not claim that GLBs are converted to KTX2 or compressed with Meshopt/Draco. Those delivery facts must be verified separately when implemented.

Lane E must not alter game state, turn logic, wallet/auth/payment logic, or renderer callbacks.
