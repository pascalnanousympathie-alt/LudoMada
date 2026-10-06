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
};

export const COSMETIC_CATALOG: CosmeticCatalogItem[] = [
  { category: "board", id: "madagascar", title: "Madagascar", subtitle: "Bois chaud • or • ivoire", priceMGA: 0n, starter: true },
  { category: "board", id: "royal_gold", title: "Royal Gold", subtitle: "Noir profond • or premium", priceMGA: 15000n },
  { category: "board", id: "ocean_night", title: "Ocean Night", subtitle: "Bleu nuit • chrome • cyan", priceMGA: 12000n },
  { category: "board", id: "baobab_sunset", title: "Baobab Sunset", subtitle: "Terre rouge • sable • coucher de soleil", priceMGA: 18000n },

  { category: "dice", id: "chrome", title: "Chrome", subtitle: "Hydro-chrome argent", priceMGA: 0n, starter: true },
  { category: "dice", id: "heroic", title: "Heroic", subtitle: "Chrome + énergie dorée", priceMGA: 8000n },
  { category: "dice", id: "crystal", title: "Crystal", subtitle: "Cristal glacé", priceMGA: 10000n },
  { category: "dice", id: "obsidian", title: "Obsidian", subtitle: "Noir miroir", priceMGA: 12000n },

  { category: "pawn", id: "lacquer", title: "Lacquer", subtitle: "Classique premium", priceMGA: 0n, starter: true },
  { category: "pawn", id: "gold", title: "Gold", subtitle: "Métal doré", priceMGA: 10000n },
  { category: "pawn", id: "gem", title: "Gem", subtitle: "Gemme brillante", priceMGA: 12000n },
  { category: "pawn", id: "obsidian", title: "Obsidian", subtitle: "Laque sombre", priceMGA: 9000n },

  { category: "vfx", id: "classic", title: "Classic", subtitle: "Poussière discrète", priceMGA: 0n, starter: true },
  { category: "vfx", id: "spark", title: "Spark", subtitle: "Étincelles premium", priceMGA: 7000n },
  { category: "vfx", id: "flame", title: "Flame", subtitle: "Burst chaud", priceMGA: 8000n },
  { category: "vfx", id: "ice", title: "Ice", subtitle: "Éclats froids", priceMGA: 9000n },
];

export function findCosmetic(category: string, id: string): CosmeticCatalogItem | undefined {
  return COSMETIC_CATALOG.find((item) => item.category === category && item.id === id);
}

export function catalogForClient(owned: Set<string>) {
  return COSMETIC_CATALOG.map((item) => ({
    category: item.category,
    id: item.id,
    title: item.title,
    subtitle: item.subtitle,
    priceMGA: item.priceMGA.toString(),
    starter: Boolean(item.starter),
    owned: item.starter === true || owned.has(item.category + ":" + item.id),
  }));
}
