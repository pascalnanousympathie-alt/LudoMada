import { randomBytes } from "node:crypto";

export const MLG_VICTORY_REWARD = 20n;
export const MLG_REFERRAL_REWARD = 100n;
export const MLG_MIN_SWAP = 10_000n;

export type MarketCategory = "board" | "die" | "pawn" | "vfx";

export const MARKET_PRICES: Record<MarketCategory, Record<string, bigint>> = {
  board: {
    royal: 0n,
    obsidian: 250n,
    sapphire: 500n,
    ruby: 750n
  },
  die: {
    chrome: 0n,
    obsidian: 200n,
    ruby: 300n,
    emerald: 300n
  },
  pawn: {
    classic: 0n,
    chrome: 200n,
    onyx: 250n,
    emerald: 300n,
    sapphire: 300n
  },
  vfx: {
    heroic: 400n,
    ember: 450n,
    frost: 450n
  }
};

export const MARKET_ITEMS = Object.entries(MARKET_PRICES).flatMap(([category, items]) =>
  Object.entries(items).map(([itemId, priceMlg]) => ({
    category: category as MarketCategory,
    itemId,
    priceMlg: priceMlg.toString()
  }))
);

export function marketPrice(category: string, itemId: string): bigint | null {
  if (!(category in MARKET_PRICES)) return null;
  const price = MARKET_PRICES[category as MarketCategory][itemId];
  return typeof price === "bigint" ? price : null;
}

export function victoryLedgerKey(gameId: string): string {
  return `game-win-mlg:${gameId}`;
}

export function referralLedgerKey(inviteeId: string): string {
  return `referral:${inviteeId}`;
}

export function newReferralCode(): string {
  return randomBytes(6).toString("base64url").slice(0, 8).toUpperCase();
}

export const FREE_COSMETICS = MARKET_ITEMS
  .filter(x => x.priceMlg === "0")
  .map(x => ({category: x.category, itemId: x.itemId}));
