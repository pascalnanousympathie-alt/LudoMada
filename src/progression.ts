/**
 * Server-authoritative player progression.
 *
 * Score is a free gameplay progression currency. It has no monetary value,
 * cannot be deposited/withdrawn, and never changes authoritative game rules.
 */
export const SCORE_FOR_COMPLETED_GAME = 100;
export const SCORE_WIN_BONUS = 300;

/** Level 1 starts at 0. Each next level gets substantially more expensive in score. */
export const LEVEL_BASE_STEP = 1250;

export type ProgressionTier = {
  id: string;
  title: string;
  badge: string;
  minLevel: number;
  perks: readonly string[];
};

/**
 * Prestige tiers are presentation/cosmetic rewards only.
 * They never modify dice, movement, capture, sortie, finish or any other game rule.
 */
export const PROGRESSION_TIERS: readonly ProgressionTier[] = [
  { id: "novice", title: "Novice", badge: "N", minLevel: 1, perks: ["Badge de profil", "Titre de niveau"] },
  { id: "eclaireur", title: "Éclaireur", badge: "E", minLevel: 5, perks: ["Badge amélioré", "Cosmétiques rares"] },
  { id: "expert", title: "Expert", badge: "X", minLevel: 10, perks: ["Badge Expert", "Cosmétiques épiques", "Effets de prestige"] },
  { id: "maitre", title: "Maître", badge: "M", minLevel: 15, perks: ["Titre Maître", "Cosmétiques héroïques", "VFX de prestige"] },
  { id: "heroique", title: "Héroïque", badge: "H", minLevel: 20, perks: ["Badge Héroïque", "Cadre de profil prestige", "Accès aux cosmétiques légendaires"] },
  { id: "legendaire", title: "Légendaire", badge: "L", minLevel: 25, perks: ["Titre Légendaire", "Badge premium", "Accès aux cosmétiques mythiques"] },
];

export function progressionTierForLevel(level: number): ProgressionTier {
  const safeLevel = Math.max(1, Math.floor(level));
  let current = PROGRESSION_TIERS[0];
  for (const tier of PROGRESSION_TIERS) {
    if (safeLevel >= tier.minLevel) current = tier;
  }
  return current;
}

export function levelThreshold(level: number): number {
  const safeLevel = Math.max(1, Math.floor(level));
  return LEVEL_BASE_STEP * (safeLevel - 1) * safeLevel / 2;
}

export function levelFromScore(score: number): number {
  const safeScore = Math.max(0, Math.floor(score));
  let level = 1;
  while (safeScore >= levelThreshold(level + 1)) level += 1;
  return level;
}

export function progressionForScore(score: number) {
  const safeScore = Math.max(0, Math.floor(score));
  const level = levelFromScore(safeScore);
  const currentLevelScore = levelThreshold(level);
  const nextLevelScore = levelThreshold(level + 1);
  const tier = progressionTierForLevel(level);
  return {
    score: safeScore,
    level,
    currentLevelScore,
    nextLevelScore,
    progressInLevel: safeScore - currentLevelScore,
    pointsToNextLevel: Math.max(0, nextLevelScore - safeScore),
    tier: tier.id,
    tierTitle: tier.title,
    tierBadge: tier.badge,
    prestigePerks: [...tier.perks],
    gameplayAdvantage: false,
    gameplayModifier: "NONE",
  };
}

export function scoreAwardForCompletedGame(won: boolean): number {
  return SCORE_FOR_COMPLETED_GAME + (won ? SCORE_WIN_BONUS : 0);
}
