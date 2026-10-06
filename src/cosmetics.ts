import { z } from "zod";

export const COSMETIC_CATEGORIES = ["board", "dice", "pawn", "vfx"] as const;
export const CosmeticCategorySchema = z.enum(COSMETIC_CATEGORIES);
export type CosmeticCategory = typeof COSMETIC_CATEGORIES[number];

export type CosmeticCatalogItem = {
  category: CosmeticCategory;
  id: string;
  title: string;
  subtitle: string;
  priceMGA: bigint;
  starter?: boolean;
  unlockLevel?: number;
  rarity?: "standard" | "rare" | "epic" | "heroic" | "legendary" | "mythic";
};

export const COSMETIC_CATALOG: CosmeticCatalogItem[] = [
  { category: "board", id: "madagascar", title: "Madagascar", subtitle: "Bois chaud • or • ivoire", priceMGA: 0n, starter: true },
  { category: "board", id: "royal_gold", title: "Royal Gold", subtitle: "Noir profond • or premium", priceMGA: 15000n, unlockLevel: 20, rarity: "legendary" },
  { category: "board", id: "ocean_night", title: "Ocean Night", subtitle: "Bleu nuit • chrome • cyan", priceMGA: 12000n, unlockLevel: 6, rarity: "epic" },
  { category: "board", id: "baobab_sunset", title: "Baobab Sunset", subtitle: "Terre rouge • sable • coucher de soleil", priceMGA: 18000n, unlockLevel: 25, rarity: "mythic" },

  { category: "dice", id: "chrome", title: "Chrome", subtitle: "Hydro-chrome argent", priceMGA: 0n, starter: true },
  { category: "dice", id: "heroic", title: "Heroic", subtitle: "Chrome + énergie dorée", priceMGA: 8000n, unlockLevel: 3, rarity: "rare" },
  { category: "dice", id: "crystal", title: "Crystal", subtitle: "Cristal glacé", priceMGA: 10000n, unlockLevel: 8, rarity: "epic" },
  { category: "dice", id: "obsidian", title: "Obsidian", subtitle: "Noir miroir", priceMGA: 12000n, unlockLevel: 12, rarity: "heroic" },

  { category: "pawn", id: "lacquer", title: "Lacquer", subtitle: "Classique premium", priceMGA: 0n, starter: true },
  { category: "pawn", id: "gold", title: "Gold", subtitle: "Métal doré", priceMGA: 10000n, unlockLevel: 4, rarity: "rare" },
  { category: "pawn", id: "gem", title: "Gem", subtitle: "Gemme brillante", priceMGA: 12000n, unlockLevel: 9, rarity: "epic" },
  { category: "pawn", id: "obsidian", title: "Obsidian", subtitle: "Laque sombre", priceMGA: 9000n, unlockLevel: 12, rarity: "heroic" },

  { category: "vfx", id: "classic", title: "Classic", subtitle: "Poussière discrète", priceMGA: 0n, starter: true },
  { category: "vfx", id: "spark", title: "Spark", subtitle: "Étincelles premium", priceMGA: 7000n, unlockLevel: 5, rarity: "rare" },
  { category: "vfx", id: "flame", title: "Flame", subtitle: "Burst chaud", priceMGA: 8000n, unlockLevel: 15, rarity: "heroic" },
  { category: "vfx", id: "ice", title: "Ice", subtitle: "Éclats froids", priceMGA: 9000n, unlockLevel: 9, rarity: "epic" },
];

export function findCosmetic(category: string, id: string): CosmeticCatalogItem | undefined {
  return COSMETIC_CATALOG.find((item) => item.category === category && item.id === id);
}

export function catalogForClient(owned: Set<string>, level = 1) {
  const safeLevel = Math.max(1, Math.floor(level));
  return COSMETIC_CATALOG.map((item) => {
    const levelUnlocked = item.unlockLevel !== undefined && safeLevel >= item.unlockLevel;
    const starter = item.starter === true;
    return {
      category: item.category,
      id: item.id,
      title: item.title,
      subtitle: item.subtitle,
      priceMGA: item.priceMGA.toString(),
      starter,
      unlockLevel: item.unlockLevel ?? null,
      rarity: item.rarity ?? "standard",
      levelUnlocked,
      owned: starter || levelUnlocked || owned.has(item.category + ":" + item.id),
    };
  });
}
