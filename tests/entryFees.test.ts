import { ENTRY_FEES_MGA, isValidEntryFee } from "../src/game/entryFees";

describe("LudoMada entry fees", () => {
  test("supports all six production-configured MGA entry fees", () => {
    expect([...ENTRY_FEES_MGA]).toEqual([500, 1000, 2000, 5000, 10000, 20000]);
    for (const fee of ENTRY_FEES_MGA) expect(isValidEntryFee(fee)).toBe(true);
  });

  test("rejects unsupported entry fees", () => {
    for (const fee of [1, 499, 3000, 15000, 25000]) expect(isValidEntryFee(fee)).toBe(false);
  });
});
