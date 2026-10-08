-- MLG production hardening: one victory reward per game/type.
-- The existing idempotency key remains unique; this DB invariant protects
-- against accidental alternate keys or concurrent reward paths.
CREATE UNIQUE INDEX "MlgLedgerEntry_gameId_type_key"
ON "MlgLedgerEntry" ("gameId", "type");
