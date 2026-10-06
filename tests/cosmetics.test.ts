import { COSMETIC_CATALOG, catalogForClient, findCosmetic } from "../src/cosmetics";

describe("cosmetic market catalog", () => {
  test("has four sellable categories with canonical prices", () => {
    expect(COSMETIC_CATALOG).toHaveLength(16);
    expect(findCosmetic("board", "royal_gold")).toMatchObject({ priceMGA: 15000n, unlockLevel: 20, rarity: "legendary" });
    expect(findCosmetic("dice", "heroic")).toMatchObject({ priceMGA: 8000n, unlockLevel: 3, rarity: "rare" });
    expect(findCosmetic("pawn", "gold")).toMatchObject({ priceMGA: 10000n, unlockLevel: 4, rarity: "rare" });
    expect(findCosmetic("vfx", "spark")).toMatchObject({ priceMGA: 7000n, unlockLevel: 5, rarity: "rare" });
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
    const items = catalogForClient(new Set(["board:royal_gold", "vfx:ice"]), 1);
    expect(items.find(x => x.category==="board" && x.id==="royal_gold")?.owned).toBe(true);
    expect(items.find(x => x.category==="vfx" && x.id==="ice")?.owned).toBe(true);
    expect(items.find(x => x.category==="dice" && x.id==="heroic")?.owned).toBe(false);
  });

  test("level unlocks become owned without a purchase", () => {
    const level2 = catalogForClient(new Set(), 2);
    expect(level2.find(x => x.category==="dice" && x.id==="heroic")).toMatchObject({
      owned:false, levelUnlocked:false, unlockLevel:3
    });
    expect(level2.find(x => x.category==="pawn" && x.id==="gold")?.owned).toBe(false);
    expect(level2.find(x => x.category==="board" && x.id==="ocean_night")?.owned).toBe(false);
    expect(catalogForClient(new Set(), 4).find(x => x.category==="pawn" && x.id==="gold")?.owned).toBe(true);

    const level4 = catalogForClient(new Set(), 8);
    expect(level4.find(x => x.category==="board" && x.id==="ocean_night")?.owned).toBe(true);
    expect(level4.find(x => x.category==="dice" && x.id==="crystal")?.owned).toBe(true);
  });

  test("client catalog exposes cosmetic rarity without affecting ownership", () => {
    const items = catalogForClient(new Set(), 20);
    expect(items.find(x=>x.category==="board" && x.id==="royal_gold")?.rarity).toBe("legendary");
    expect(items.find(x=>x.category==="dice" && x.id==="obsidian")?.rarity).toBe("heroic");
    expect(items.find(x=>x.category==="vfx" && x.id==="flame")?.rarity).toBe("heroic");
  });

  test("unknown cosmetics are rejected from server catalog lookup", () => {
    expect(findCosmetic("board", "does_not_exist")).toBeUndefined();
    expect(findCosmetic("unknown", "royal_gold")).toBeUndefined();
  });
});
