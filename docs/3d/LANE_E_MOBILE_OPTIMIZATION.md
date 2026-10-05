# Lane E — mobile 3D asset optimization

## Target
Keep physically convincing assets while controlling APK size, memory and GPU workload on Android.

## Pipeline
1. Author in Blender / Higgsfield 3D Jutsu / Meshy.
2. Export glTF 2.0 / GLB.
3. Prefer Draco or Meshopt geometry compression.
4. Prefer KTX2/Basis textures when textures are introduced.
5. Validate hard budgets before runtime integration.

## Default budgets
- hero gameplay GLB: <= 8 MiB
- individual optional prop: <= 2 MiB
- 2K texture only when needed for close-up hero assets
- avoid uncompressed 4K textures in the gameplay APK
- keep path/home cells as shared or instanced geometry at runtime where possible

## Current source scene
Higgsfield 3D Jutsu project 083cab86-9817-4ddc-8d12-92d9212d008b, revision 2.
The latest committed GLB is 846100 bytes (~0.81 MiB), comfortably inside the hero budget.

## Runtime tiers
MOBILE_SAFE:
- 256px shadow target
- reduced post effects
- low particle count

BALANCED:
- 512px shadow target
- moderate SSAO/bloom
- normal map detail where available

PREMIUM:
- 1024px shadow target only when device profiling proves safe
- full post stack where budget allows

The game state must never depend on the selected render tier.
