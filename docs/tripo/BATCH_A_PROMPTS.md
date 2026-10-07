# LudoMada — Tripo 3D Batch A

Production asset brief for the current GLES3/PBR renderer. These assets are cosmetic/rendering only and MUST NOT alter authoritative game state, movement, capture, sortie, finish, wallet, auth, payments, reconciliation, or anti-cheat.

## Global Tripo export rules

- Export: GLB / glTF 2.0
- Real-time mobile target
- Prefer Smart Mesh / low-poly where available
- Preserve PBR materials
- Export Base Color, Normal, Roughness and Metallic maps when available
- No baked environment/background in the asset
- No text, UI, logos, watermark, or floating geometry
- Apply real-world sensible scale
- Keep topology clean and watertight
- Keep UVs non-overlapping except intentional mirrored/tiling regions
- Prefer one logical object per asset
- Target triangle budgets are guidance, not a reason to destroy silhouette quality

## Asset 01 — LM_Die_Premium_v2.glb

**Prompt**

Premium realistic physical six-sided game die for a luxury Madagascar Ludo board game. Single manufactured object, rounded chamfered cube, slightly imperfect real-world edges, white/silver hydro-chrome body, deeply recessed circular pips with smooth cavity walls and bevels, physically plausible proportions, subtle micro-surface manufacturing imperfections, premium board-game quality, no text, no logo, no background objects, isolated asset, real-time mobile game ready.

**Target:** 10k–20k triangles. PBR: base color + normal + roughness + metallic.

**Acceptance:** pips must be actual recessed geometry, not painted dots.

## Asset 02 — LM_Die_Pip_Insert.glb

**Prompt**

Small reusable recessed die pip insert for a premium physical game die. Smooth circular cavity, rounded inner bevel, realistic dark reflective interior, physically manufactured surface, optimized for repeated instancing on a mobile realtime die, isolated object, no background.

**Target:** 500–2k triangles.

**Acceptance:** optional fallback asset only; do not use if Asset 01 already contains excellent physical pips.

## Asset 03 — LM_Table_Premium.glb

**Prompt**

Premium physical wooden tabletop for a luxury four-player Ludo board game. Thick solid wood slab, realistic beveled perimeter, subtle hand-crafted imperfections, natural wood grain, realistic roughness variation, slightly polished game-table finish, believable thickness and underside, no Ludo markings, no game pieces, no background, isolated object.

**Target:** 20k–40k triangles. PBR maps required.

## Asset 04 — LM_Board_Frame_Detail.glb

**Prompt**

Luxury carved wooden frame for a premium four-player Ludo board. Deep beveled edges, layered physical construction, subtle Madagascar-inspired geometric carving, realistic wood grain, restrained premium craftsmanship, physically manufactured object, isolated asset, no text, no logo, no board cells.

**Target:** 10k–25k triangles.

## Asset 05 — LM_Rock_01.glb

**Prompt**

Photorealistic natural Madagascar rock, irregular geological silhouette, weathered erosion, porous stone surface, subtle dust and dirt deposits, realistic roughness variation, physically plausible scale, isolated game-ready mobile asset, no plants, no background.

**Target:** 3k–8k triangles.

## Asset 06 — LM_Rock_02.glb

Same specification as Rock 01, but with a clearly different silhouette, surface breakup and material variation. Avoid duplicate geometry.

## Asset 07 — LM_DryBranch_01.glb

**Prompt**

Photorealistic dry branch from a Madagascar dry-season landscape. Naturally twisted wood, cracked bark, small broken twigs, irregular asymmetrical silhouette, aged wood material, realistic roughness and subtle color variation, isolated game-ready mobile asset, no leaves, no background.

**Target:** 3k–8k triangles.

## Asset 08 — LM_DryBranch_02.glb

Same specification as DryBranch 01, but with a substantially different natural silhouette and branching pattern.

## Asset 09 — LM_Grass_Clump_01.glb

**Prompt**

Photorealistic dry tropical grass clump from Madagascar. Individual stems and small leaves, natural irregular silhouette, subtle wind-bent variation, realistic dry plant material, optimized for mobile realtime rendering, isolated object, no background.

**Target:** 2k–6k triangles.

## Asset 10 — LM_Shrub_01.glb

**Prompt**

Photorealistic small Madagascar dry-season shrub. Dense but readable foliage, natural irregular silhouette, realistic stems and leaves, believable material response, restrained polygon count for mobile realtime rendering, isolated game asset, no background.

**Target:** 5k–12k triangles.

## Asset 11 — LM_GroundCluster_01.glb

**Prompt**

Small photorealistic Madagascar dry-ground clutter cluster containing several natural stones, dry leaves, short twigs, tiny dirt clumps and grass fragments. Cohesive real-world grouping, varied micro silhouettes, realistic materials, optimized as a reusable mobile scene prop, isolated asset, no large ground plane.

**Target:** 5k–12k triangles.

## Asset 12 — LM_Trophy_LudoMada.glb

**Prompt**

Premium tournament trophy for a luxury Madagascar Ludo game. Elegant polished gold-metal cup, subtle Madagascar-inspired geometric ornamentation, realistic manufactured seams and bevels, premium esports/board-game presentation, dedicated circular emblem area but absolutely no text, no logo, isolated object, physically plausible proportions.

**Target:** 5k–15k triangles. Metallic PBR.

# Integration contract

Place accepted exports under:

android-app/app/src/main/assets/3d/tripo/batch-a/

Expected filenames:

LM_Die_Premium_v2.glb
LM_Die_Pip_Insert.glb
LM_Table_Premium.glb
LM_Board_Frame_Detail.glb
LM_Rock_01.glb
LM_Rock_02.glb
LM_DryBranch_01.glb
LM_DryBranch_02.glb
LM_Grass_Clump_01.glb
LM_Shrub_01.glb
LM_GroundCluster_01.glb
LM_Trophy_LudoMada.glb

Do not replace the current authored Ludo board GLB, pawn GLB, die GLB, baobab GLB, or authoritative gameplay code until the new asset passes validation and a visual A/B comparison.

# Priority

P0: Die
P0: Table
P0: Board frame
P1: Rocks / dry branches / grass / shrub / ground cluster
P2: Trophy

# Acceptance gate

An asset is accepted only when:

1. GLB opens correctly.
2. Geometry is manifold enough for realtime rendering.
3. Triangle budget is within target or has a documented reason.
4. PBR maps are present when requested.
5. No baked background/camera/light rig is required.
6. It preserves the intended silhouette under mobile PBR lighting.
7. It does not force changes to authoritative game logic.
8. It survives APK packaging and Ramus visual smoke testing.
