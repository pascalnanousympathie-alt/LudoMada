-- Add indexes for foreign keys queried from their referencing side.
-- These are safe, additive indexes and do not change application data.
CREATE INDEX "Transaction_gameId_idx" ON "Transaction"("gameId");
CREATE INDEX "GameParticipant_userId_idx" ON "GameParticipant"("userId");
