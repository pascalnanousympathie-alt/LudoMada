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

The Home screen exposes a compact Market icon and a unified navigation menu. Profile links to Personalisation. Each catalog entry updates local selection immediately and refreshes the active board renderer when one is present.


## Free score and level unlocks

Completed online games award free progression score on the authoritative server:
- +100 score to every participant who completes the game.
- +300 winner bonus, for +400 total to the winner.
- Entry fee does not affect score, and score has no monetary value.

Level thresholds are intentionally demanding: level 2 at 1,250 score, level 3 at 3,750, level 4 at 7,500, level 5 at 12,500, then continuing upward by an increasing triangular step.

Progression has a prestige tier layer: Novice (L1), Éclaireur (L5), Expert (L10), Maître (L15), Héroïque (L20) and Légendaire (L25). These tiers provide presentation/social rewards such as badges, titles, profile framing, lobby intros, emotes and victory VFX, plus access to higher-rarity cosmetics.

**Fair-play invariant:** level, tier, score and cosmetics never modify dice probability, legal moves, movement distance, sortie, capture, exact finish, turn order, win condition or payout. A L1 player and a L25 player can join the same room and play the exact same ruleset. The lobby exposes each player's level/title for identity only; matchmaking is not blocked by level.

Cosmetics carry a rarity (standard, rare, epic, heroic, legendary, mythic) for prestige. Reaching the required level marks the cosmetic as owned for free; buying it early is an optional wallet purchase. Starter cosmetics are always free. The market remains a compact secondary utility in the Home hierarchy.