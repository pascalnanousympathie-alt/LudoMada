import { LUDO_MADA_RULESET_V1, validateRuleset } from "./ruleset";

export function getActiveRuleset() {
  validateRuleset(LUDO_MADA_RULESET_V1);
  return LUDO_MADA_RULESET_V1;
}
