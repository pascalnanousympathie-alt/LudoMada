# GitHub/Vercel deployment automation

## Project mapping

- GitHub repository: `pascalnanousympathie-alt/LudoMada`
- Vercel project intended for Git-backed deployment: `mada-commander-public-mcp-auto`
- Root directory: `tools/mada-commander-public-mcp`
- Production branch: `main`

The Git-backed project was created from the Vercel GitHub integration and can be built from a Git reference. Validate on a fresh pull request and then on a merge to `main` that the Vercel Git integration automatically creates a deployment. A manual API deployment alone is not proof that the push webhook is active.

## Production safety gate

The service must remain fail-closed until all of these are true:

- A dedicated Supabase/Postgres `MADA_COMMANDER_DATABASE_URL` is installed in Vercel Production and the schema migration completes.
- `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET` are correctly configured and real Google OAuth sign-in has been exercised.
- `OPENAI_APPS_CHALLENGE_TOKEN` contains the exact value issued by the OpenAI Plugin submission portal and `/.well-known/openai-apps-challenge` returns that exact value as plain text.
- `npm run verify`, `npm audit --omit=dev --audit-level=high`, `npm run smoke:production -- --expect-ready`, and the two-account tenant-isolation test pass.

Do not transfer the existing public alias or disable any fail-closed check until the new Git-backed deployment passes those conditions.
