ALTER TABLE "User" ADD COLUMN "googleSub" TEXT;
CREATE UNIQUE INDEX "User_googleSub_key" ON "User"("googleSub");

CREATE TABLE "GoogleAuthChallenge" (
    "id" TEXT NOT NULL,
    "nonceHash" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "consumedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "GoogleAuthChallenge_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "GoogleAuthChallenge_nonceHash_key"
    ON "GoogleAuthChallenge"("nonceHash");
CREATE INDEX "GoogleAuthChallenge_expiresAt_idx"
    ON "GoogleAuthChallenge"("expiresAt");
