import { COSMETIC_CATALOG, catalogForClient, findCosmetic } from "../src/cosmetics";

describe("cosmetic market catalog", () => {
  test("has four sellable categories with canonical prices", () => {
    expect(COSMETIC_CATALOG).toHaveLength(16);
    expect(findCosmetic("board", "royal_gold")).toMatchObject({ priceMGA: 15000n });
    expect(findCosmetic("dice", "heroic")).toMatchObject({ priceMGA: 8000n });
    expect(findCosmetic("pawn", "gold")).toMatchObject({ priceMGA: 10000n });
    expect(findCosmetic("vfx", "spark")).toMatchObject({ priceMGA: 7000n });
  });

  test("starter cosmetics are owned without a purchase", () => {
    const items = catalogForClient(new Set());
    expect(items.filter(x => x.owned)).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ category: "board", id: "madagascar", owned: true }),
        expect.objectContaining({ category: "dice", id: "chrome", owned: true }),
        expect.objectContaining({ category: "pawn", id: "lacquer", owned: true }),
        expect.objectContaining({ category: "vfx", id: "classic", owned: true }),
      ])
    );
  });

  test("purchased cosmetics are exposed as owned", () => {
    const items = catalogForClient(new Set(["board:royal_gold", "vfx:ice"]));
    expect(items.find(x => x.category==="board" && x.id==="royal_gold")?.owned).toBe(true);
    expect(items.find(x => x.category==="vfx" && x.id==="ice")?.owned).toBe(true);
    expect(items.find(x => x.category==="dice" && x.id==="heroic")?.owned).toBe(false);
  });

  test("unknown cosmetics are rejected from server catalog lookup", () => {
    expect(findCosmetic("board", "does_not_exist")).toBeUndefined();
    expect(findCosmetic("unknown", "royal_gold")).toBeUndefined();
  });
});
