-- =========================================================
-- 0002: Lobby / Game creator / WAITING and PLAYING states
-- =========================================================

-- 1. Add enum values.
-- These statements are intentionally outside BEGIN/COMMIT.
ALTER TYPE "GameStatus" ADD VALUE IF NOT EXISTS 'WAITING';
ALTER TYPE "GameStatus" ADD VALUE IF NOT EXISTS 'PLAYING';

-- 2. Existing CREATED games are already old active games.
UPDATE "Game"
SET status = 'PLAYING'
WHERE status = 'CREATED';

-- 3. Add creatorId temporarily nullable.
ALTER TABLE "Game"
ADD COLUMN IF NOT EXISTS "creatorId" TEXT;

-- 4. Backfill creatorId using the earliest participant.
WITH ranked_participants AS (
  SELECT
    "gameId",
    "userId",
    ROW_NUMBER() OVER (
      PARTITION BY "gameId"
      ORDER BY "createdAt" ASC, id ASC
    ) AS rn
  FROM "GameParticipant"
)
UPDATE "Game" g
SET "creatorId" = rp."userId"
FROM ranked_participants rp
WHERE rp."gameId" = g.id
  AND rp.rn = 1
  AND g."creatorId" IS NULL;

-- 5. Abort if an existing Game has no creator.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM "Game"
    WHERE "creatorId" IS NULL
  ) THEN
    RAISE EXCEPTION
      'Migration aborted: one or more Game rows have no creatorId';
  END IF;
END $$;

-- 6. creatorId becomes required.
ALTER TABLE "Game"
ALTER COLUMN "creatorId" SET NOT NULL;

-- 7. Foreign key.
ALTER TABLE "Game"
DROP CONSTRAINT IF EXISTS "Game_creatorId_fkey";

ALTER TABLE "Game"
ADD CONSTRAINT "Game_creatorId_fkey"
FOREIGN KEY ("creatorId")
REFERENCES "User"(id)
ON DELETE RESTRICT
ON UPDATE CASCADE;

-- 8. Lobby indexes.
CREATE INDEX IF NOT EXISTS "Game_creatorId_createdAt_idx"
ON "Game" ("creatorId", "createdAt");

CREATE INDEX IF NOT EXISTS "Game_status_createdAt_idx"
ON "Game" (status, "createdAt");
