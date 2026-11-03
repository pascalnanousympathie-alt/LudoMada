-- Add indexes for two foreign-key columns identified by Supabase's performance advisor.
-- Existing composite indexes are not sufficient for lookups by userId alone in GameParticipant,
-- or by gameId alone in Transaction.
CREATE INDEX IF NOT EXISTS "Transaction_gameId_idx" ON "Transaction"("gameId");
CREATE INDEX IF NOT EXISTS "GameParticipant_userId_idx" ON "GameParticipant"("userId");
