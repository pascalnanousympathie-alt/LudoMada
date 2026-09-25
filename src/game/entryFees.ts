export const ENTRY_FEES_MGA = Object.freeze([500, 1_000, 2_000, 5_000, 10_000, 20_000] as const);

export function isValidEntryFee(value: number): value is (typeof ENTRY_FEES_MGA)[number] {
  return ENTRY_FEES_MGA.includes(value as (typeof ENTRY_FEES_MGA)[number]);
}
