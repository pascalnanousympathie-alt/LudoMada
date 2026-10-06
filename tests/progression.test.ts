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
    expect(levelThreshold(2)).toBe(250);
    expect(levelThreshold(3)).toBe(750);
    expect(levelThreshold(4)).toBe(1500);
    expect(levelFromScore(0)).toBe(1);
    expect(levelFromScore(249)).toBe(1);
    expect(levelFromScore(250)).toBe(2);
    expect(levelFromScore(749)).toBe(2);
    expect(levelFromScore(750)).toBe(3);
  });

  test("every completed game gives free score and the winner gets a bonus", () => {
    expect(scoreAwardForCompletedGame(false)).toBe(SCORE_FOR_COMPLETED_GAME);
    expect(scoreAwardForCompletedGame(true)).toBe(SCORE_FOR_COMPLETED_GAME + SCORE_WIN_BONUS);
  });

  test("progress payload exposes current and next level progress", () => {
    expect(progressionForScore(400)).toEqual({
      score:400,
      level:2,
      currentLevelScore:250,
      nextLevelScore:750,
      progressInLevel:150,
      pointsToNextLevel:350,
    });
  });

  test("negative score is normalized to zero", () => {
    expect(progressionForScore(-50)).toMatchObject({score:0,level:1});
  });
});
