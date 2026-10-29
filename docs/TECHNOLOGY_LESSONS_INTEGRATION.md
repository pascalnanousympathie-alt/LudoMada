# Technology lessons applied to LudoMada

**Status:** implementation branch; not merged or deployed.

## What the reference projects teach us

- **Real-time multiplayer (Socket.IO/WebSockets):** game clients need event-driven updates, not rapid polling alone. LudoMada keeps PostgreSQL's authoritative state and exposes a small Server-Sent Events (SSE) notification channel first. It avoids adding another real-time dependency before the Android client and multi-instance needs are measured.
- **Live game state (Redis/RedisJSON):** a fast ephemeral store can help at scale, but must not replace LudoMada's durable PostgreSQL state, command idempotency, and transaction ledger. The current event hub is process-local; it is a starter transport, not cross-instance fanout. Add Redis Pub/Sub or a Socket.IO Redis adapter only when deploying multiple API instances and after testing reconnect/replay semantics.
- **Firebase:** useful for notifications or optional identity integrations, but adding a second authoritative database for games/wallets would create split-brain risk. PostgreSQL remains the source of truth. Do not add Firebase credentials or grant it wallet/game write authority as part of this adoption.
- **AI opponents:** a deterministic, explainable heuristic can prioritize winning, capturing, entering a pawn, progress, safe squares, and avoiding exposure. This branch adds a pure AI move chooser and a read-only authenticated suggestion endpoint; it does not yet create persisted bot identities or automate a bot turn.
- **Android UI (Jetpack Compose):** Compose is a useful incremental screen technology, but a wholesale UI rewrite is not required for backend real-time and AI strategy. Introduce it screen-by-screen only after the current Android gameplay branch is reconciled with main.
- **3D assets and cosmetics:** GLB/GLES3 scene loading and Market V6 already exist in separate open feature PRs (#69 and #73), not in this main baseline. Review and integrate those product branches as a separate, ordered Android workstream; do not cherry-pick individual 3D assets without their loader, manifest, renderer and validation checks.
- **Go backend:** the reference Go implementation demonstrates an alternative for performance-sensitive services, not a reason to rewrite a stable TypeScript/Express API. Profile gameplay and database latency first; keep one authoritative backend until metrics justify a separate service.

## Implemented in this branch

1. Authenticated participant-only SSE endpoint: GET /api/games/:gameId/events.
2. Event notifications after participant join, game start, dice roll and token move. Events contain only the game ID, reason and state version; clients re-fetch the participant-authorized state endpoint after each notification.
3. Bounded stream subscriptions (64 per game, 4 per user) with disconnect cleanup and keep-alive comments.
4. Pure deterministic AI move selection with tests, plus GET /api/games/:gameId/ai-suggestion. The endpoint does not mutate game state, roll dice, settle a game, or change wallet/payment data.
5. Regression tests for stream scoping/cleanup and AI move selection.

## Limits and next integration gates

- The event hub is in-memory and works only for clients connected to the same API process. Before horizontal scaling, add a shared fanout adapter and define reconnect/snapshot semantics. PostgreSQL remains the canonical state.
- The Android online board now opens the authenticated SSE stream, refetches authoritative state on game updates, and includes a manual AI hint button. The existing 4-second state refresh remains as a fallback. The 3D/cosmetics feature branches still need separate integration and emulator validation.
- Existing CI in main still uses the self-hosted ARM64 runner. The open PR #139 contains a hosted-runner fix and reported passing backend/security/release checks on its head, but that PR is not merged by this change. Do not mask a runner failure as a test pass.
- No database migration, third-party runtime dependency, production deployment, payment switch, wallet logic, or live credential change is included.
