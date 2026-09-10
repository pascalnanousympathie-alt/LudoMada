export interface LudoRuleset {
  id: string;
  version: number;
  playerCountMin: number;
  playerCountMax: number;
  tokensPerPlayer: number;
  entryRoll: number;
  extraTurnOnSix: boolean;
  extraTurnOnCapture: boolean;
  safeSquares: number[];
  exactFinish: boolean;
  captureOwnStack: boolean;
}

export const LUDO_MADA_RULESET_V1: Readonly<LudoRuleset> = Object.freeze({
  id: "LUDO_MADA_CLASSIC",
  version: 1,
  playerCountMin: 2,
  playerCountMax: 4,
  tokensPerPlayer: 4,
  entryRoll: 6,
  extraTurnOnSix: true,
  extraTurnOnCapture: true,
  safeSquares: [0, 8, 13, 21, 26, 34, 39, 47],
  exactFinish: true,
  captureOwnStack: false
});

export function validateRuleset(r: LudoRuleset): void {
  if (r.playerCountMin < 2 || r.playerCountMax > 4 || r.playerCountMin > r.playerCountMax)
    throw new Error("INVALID_PLAYER_RANGE");
  if (r.tokensPerPlayer !== 4) throw new Error("INVALID_TOKEN_COUNT");
  if (r.entryRoll !== 6) throw new Error("INVALID_ENTRY_RULE");
  if (r.exactFinish !== true) throw new Error("REAL_MONEY_REQUIRES_EXACT_FINISH");
  if (new Set(r.safeSquares).size !== r.safeSquares.length) throw new Error("DUPLICATE_SAFE_SQUARE");
}
