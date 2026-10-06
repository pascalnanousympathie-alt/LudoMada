/**
 * Server-authoritative player progression.
 *
 * Score is a free gameplay progression currency. It has no monetary value,
 * cannot be deposited/withdrawn, and never changes authoritative game rules.
 */
export const SCORE_FOR_COMPLETED_GAME = 100;
export const SCORE_WIN_BONUS = 300;

/** Level 1 starts at 0; each next level needs 250 more points than the previous one. */
export const LEVEL_BASE_STEP = 250;

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
  return {
    score: safeScore,
    level,
    currentLevelScore,
    nextLevelScore,
    progressInLevel: safeScore - currentLevelScore,
    pointsToNextLevel: Math.max(0, nextLevelScore - safeScore),
  };
}

export function scoreAwardForCompletedGame(won: boolean): number {
  return SCORE_FOR_COMPLETED_GAME + (won ? SCORE_WIN_BONUS : 0);
}
