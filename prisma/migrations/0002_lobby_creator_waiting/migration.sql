-- 0002: add the new enum values in their own migration.
-- PostgreSQL requires a commit before a newly-added enum value can be used.
ALTER TYPE "GameStatus" ADD VALUE IF NOT EXISTS 'WAITING';
ALTER TYPE "GameStatus" ADD VALUE IF NOT EXISTS 'PLAYING';
