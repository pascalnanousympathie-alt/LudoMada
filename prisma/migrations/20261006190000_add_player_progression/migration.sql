ALTER TABLE "User"
  ADD COLUMN "score" INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN "level" INTEGER NOT NULL DEFAULT 1;

CREATE INDEX "User_level_score_idx" ON "User"("level","score");
