import {
  MLG_MIN_SWAP,
  MLG_REFERRAL_REWARD,
  MLG_VICTORY_REWARD,
  MARKET_ITEMS,
  FREE_COSMETICS,
  marketPrice,
  newReferralCode,
  victoryLedgerKey,
  referralLedgerKey
} from "../src/mlgEconomy";

describe("MLG economy invariants", () => {
  test("reward rules are exact", () => {
    expect(MLG_VICTORY_REWARD).toBe(20n);
    expect(MLG_REFERRAL_REWARD).toBe(100n);
    expect(MLG_MIN_SWAP).toBe(10_000n);
  });

  test("victory idempotency is game-scoped and stable", () => {
    expect(victoryLedgerKey("game-1")).toBe("game-win-mlg:game-1");
    expect(victoryLedgerKey("game-1")).toBe(victoryLedgerKey("game-1"));
    expect(victoryLedgerKey("game-1")).not.toBe(victoryLedgerKey("game-2"));
  });

  test("referral reward idempotency is invitee-scoped and stable", () => {
    expect(referralLedgerKey("invitee-1")).toBe("referral:invitee-1");
    expect(referralLedgerKey("invitee-1")).not.toBe(referralLedgerKey("invitee-2"));
  });

  test("market prices are server-defined and non-negative", () => {
    expect(MARKET_ITEMS.length).toBeGreaterThan(0);
    for (const item of MARKET_ITEMS) {
      expect(BigInt(item.priceMlg)).toBeGreaterThanOrEqual(0n);
      expect(marketPrice(item.category, item.itemId)?.toString()).toBe(item.priceMlg);
    }
    expect(FREE_COSMETICS).toEqual(
      MARKET_ITEMS
        .filter(item => item.priceMlg === "0")
        .map(item => ({ category: item.category, itemId: item.itemId }))
    );
  });

  test("referral codes match the public format", () => {
    const code = newReferralCode();
    expect(code).toMatch(/^[A-Z0-9_-]{8}$/);
  });
});
