-- 0003: lobby / creator schema changes after enum values are committed.
UPDATE "Game" SET status = 'PLAYING' WHERE status = 'CREATED';

ALTER TABLE "Game" ADD COLUMN IF NOT EXISTS "creatorId" TEXT;

WITH ranked_participants AS (
  SELECT "gameId", "userId",
    ROW_NUMBER() OVER (PARTITION BY "gameId" ORDER BY "createdAt" ASC, id ASC) AS rn
  FROM "GameParticipant"
)
UPDATE "Game" g
SET "creatorId" = rp."userId"
FROM ranked_participants rp
WHERE rp."gameId" = g.id AND rp.rn = 1 AND g."creatorId" IS NULL;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM "Game" WHERE "creatorId" IS NULL) THEN
    RAISE EXCEPTION 'Migration aborted: one or more Game rows have no creatorId';
  END IF;
END $$;

ALTER TABLE "Game" ALTER COLUMN "creatorId" SET NOT NULL;
ALTER TABLE "Game" DROP CONSTRAINT IF EXISTS "Game_creatorId_fkey";
ALTER TABLE "Game" ADD CONSTRAINT "Game_creatorId_fkey"
  FOREIGN KEY ("creatorId") REFERENCES "User"(id)
  ON DELETE RESTRICT ON UPDATE CASCADE;

CREATE INDEX IF NOT EXISTS "Game_creatorId_createdAt_idx" ON "Game" ("creatorId", "createdAt");
CREATE INDEX IF NOT EXISTS "Game_status_createdAt_idx" ON "Game" (status, "createdAt");
