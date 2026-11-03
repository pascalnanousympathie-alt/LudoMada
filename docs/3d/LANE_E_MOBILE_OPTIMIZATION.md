# Lane E — mobile 3D optimization contract

Lane E protects physical visual quality while controlling APK size, memory and GPU cost on Android.

## Current asset state

The live 3D manifest records:
- Higgsfield 3D Jutsu project `083cab86-9817-4ddc-8d12-92d9212d008b`
- revision 2
- source GLB size 846100 bytes
- `runtime_packaged: false`

The source asset budget is a contract, not proof that a production GLB is already bundled in the APK.

## Budget

Hero gameplay GLB: <= 8 MiB.
Optional prop GLB: <= 2 MiB.
Gameplay texture target: <= 2048 px where detail is actually needed.
KTX2/Basis is preferred for texture delivery.
Meshopt or Draco is preferred for geometry compression.
Large repeated meshes should be shared/instanced rather than duplicated.

## Runtime tiers

`MOBILE_SAFE`: 256px shadow target, reduced post effects, capped particles.
`BALANCED`: 384px shadow target, moderate post effects, medium particle budget.
`PREMIUM`: 512px shadow target, full current post stack, 128 particle cap.

The current renderer's normal premium path is already 512px; 1024px is intentionally not the default for the target mobile class.

## Validation semantics

`scripts/check-3d-optimization.mjs` validates the manifest, recorded source GLB size, any bundled `.glb` sizes, explicit packaging state, and tier budget ordering.

A passing optimization contract does not claim that a GLB is bundled, converted to KTX2, or compressed by Meshopt/Draco. Those delivery facts must be proven when assets are imported.

Lane E must not alter game state, turn logic, wallet/auth/payment logic, or renderer callbacks.