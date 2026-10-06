# LudoMada Cosmetic / Asset System

The 3D renderer consumes one cosmetic snapshot per board view. The snapshot contains only visual choices: board style, dice style, pawn style and VFX style.

## Runtime flow

CosmeticAssetSystem.snapshot(Context) -> LudoBoardView.cosmeticSnapshot -> GLES2/GLES3 renderer.

Selections are stored locally in Android SharedPreferences under ludomada_cosmetics. A renderer refresh reloads only the visual snapshot and requests a frame.

## Current catalog

Boards: Madagascar, Royal Gold, Ocean Night, Baobab Sunset.
Dice: Chrome, Heroic, Crystal, Obsidian.
Pawns: Lacquer, Gold, Gem, Obsidian.
VFX: Classic, Spark, Flame, Ice.

The current implementation uses procedural geometry/material parameters. The assetPath fields and cosmetics/ root reserve the import contract for licensed GLB/glTF assets later.

## Asset import contract

Licensed GLB/glTF assets must target metre scale, document semantic parts/pivot/material, avoid external texture dependencies, stay within the mobile size limit, and preserve the gameplay renderer's interaction projection.

A licensed asset can replace a procedural fallback without changing game rules.

## Integration boundary

Do not move authoritative logic into this layer. Turn validation, dice outcome, sortie, movement, capture, arrival, exact finish, winner state, reconciliation, wallet/ledger, authentication, payments and anti-cheat remain outside the cosmetic system.

## UI

The Home screen exposes a visible Personalisation button. Profile also links to the same menu. Each catalog entry updates local selection immediately and refreshes the active board renderer when one is present.
