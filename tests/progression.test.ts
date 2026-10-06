import {
  SCORE_FOR_COMPLETED_GAME,
  SCORE_WIN_BONUS,
  levelFromScore,
  levelThreshold,
  progressionForScore,
  progressionTierForLevel,
  PROGRESSION_TIERS,
  scoreAwardForCompletedGame,
} from "../src/progression";

describe("player progression", () => {
  test("level thresholds are deterministic", () => {
    expect(levelThreshold(1)).toBe(0);
    expect(levelThreshold(2)).toBe(1250);
    expect(levelThreshold(3)).toBe(3750);
    expect(levelThreshold(4)).toBe(7500);
    expect(levelThreshold(5)).toBe(12500);
    expect(levelFromScore(0)).toBe(1);
    expect(levelFromScore(1249)).toBe(1);
    expect(levelFromScore(1250)).toBe(2);
    expect(levelFromScore(3749)).toBe(2);
    expect(levelFromScore(3750)).toBe(3);
  });

  test("prestige tiers change presentation, never gameplay fairness", () => {
    expect(PROGRESSION_TIERS.map(x => x.minLevel)).toEqual([1, 5, 10, 15, 20, 25]);
    expect(progressionTierForLevel(1).id).toBe("novice");
    expect(progressionTierForLevel(10).id).toBe("expert");
    expect(progressionTierForLevel(20).id).toBe("heroique");
    expect(progressionTierForLevel(25).id).toBe("legendaire");

    const progression = progressionForScore(237500);
    expect(progression.tierTitle).toBe("Héroïque");
    expect(progression.gameplayAdvantage).toBe(false);
    expect(progression.gameplayModifier).toBe("NONE");
    expect(progression.prestigePerks.length).toBeGreaterThan(0);
  });

  test("every completed game gives free score and the winner gets a bonus", () => {
    expect(scoreAwardForCompletedGame(false)).toBe(SCORE_FOR_COMPLETED_GAME);
    expect(scoreAwardForCompletedGame(true)).toBe(SCORE_FOR_COMPLETED_GAME + SCORE_WIN_BONUS);
  });

  test("progress payload exposes current and next level progress", () => {
    expect(progressionForScore(1600)).toEqual({
      score:1600,
      level:2,
      currentLevelScore:1250,
      nextLevelScore:3750,
      progressInLevel:350,
      pointsToNextLevel:2150,
      tier:"novice",
      tierTitle:"Novice",
      tierBadge:"N",
      prestigePerks:["Badge de profil","Titre Novice"],
      gameplayAdvantage:false,
      gameplayModifier:"NONE",
    });
  });

  test("negative score is normalized to zero", () => {
    expect(progressionForScore(-50)).toMatchObject({score:0,level:1});
  });
});
