# LudoMada Premium 3D Asset Pipeline

This directory defines the runtime-neutral contract for physically convincing gameplay assets.

## Runtime target

Primary target: Android real-time rendering on the existing LudoMada gameplay renderer.

Authoring:
- Blender / Higgsfield 3D Jutsu
- Meshy when AI-assisted modelling or texturing is useful

Delivery:
- glTF 2.0 / GLB
- metre scale
- descriptive semantic object names
- Principled PBR materials
- no required external texture paths

## Asset classes

### Pawn
Required semantic parts:
- weighted base
- tapered body
- collar/neck transition
- spherical head

Target properties:
- real geometry; no baked pawn silhouette
- smooth shading
- bevelled highlights
- lacquer material
- visible contact shadow
- pivot at the base/contact plane

### Die
Required:
- bevelled six-sided cube
- physically rounded edges
- embedded/inset pips
- hydro-plated chrome/silver material

Target material:
- metallic: 0.90–1.00
- roughness: 0.05–0.16
- strong but controlled Fresnel/specular response

### Table/board
Required:
- actual thickness/depth
- recessed play surface
- separate frame
- material separation between table, board and trim
- stable contact plane

## Mobile budget

Prefer:
- shared meshes/materials
- moderate polygon density
- normal maps instead of unnecessary geometry detail
- compressed textures when textures are introduced
- LOD tiers when an asset becomes expensive

Do not add an asset to runtime until its geometry, material, pivot and scale are documented.

## Integration rule

This pipeline must not modify authoritative game state, turn logic, sortie/capture/arrival/exact-finish, reconciliation, wallet/ledger, auth, or payment-provider logic.
