import {
  SCORE_FOR_COMPLETED_GAME,
  SCORE_WIN_BONUS,
  levelFromScore,
  levelThreshold,
  progressionForScore,
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
    });
  });

  test("negative score is normalized to zero", () => {
    expect(progressionForScore(-50)).toMatchObject({score:0,level:1});
  });
});
