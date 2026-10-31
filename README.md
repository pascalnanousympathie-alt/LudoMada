# LudoMada
Ludo Madagascar Online Game

# Ludo Mada Backend V1

Prototype backend for Wallet + Transaction Ledger.

Stack:
- Node.js
- TypeScript
- Express
- PostgreSQL
- JWT
- Prisma

Features:
- Register/login
- Wallet balance
- Immutable-style transaction ledger
- Deposit/withdraw request creation
- Transaction history
- Idempotency key for money-moving requests
- Server-side balance updates in DB transactions

Payment provider calls are disabled by default. A wallet deposit or withdrawal for a disabled provider is rejected before a transaction or wallet mutation is created. Do not enable a provider merely by injecting credentials: MVola and Binance each require their own explicit live switch (`PAYMENTS_MVOLA_LIVE_ENABLED=true` or `PAYMENTS_BINANCE_LIVE_ENABLED=true`) as well as the approved provider contract and operational approval. Airtel Money and Orange Money remain non-networking until their official merchant API contracts are supplied and reviewed.

## Run

1. Copy `.env.example` to `.env`
2. Set `DATABASE_URL` and `JWT_SECRET`
3. `npm install`
4. `npx prisma migrate dev --name init`
5. `npm run dev`

API:
- POST /api/auth/register
- POST /api/auth/login
- GET /api/wallet
- GET /api/wallet/transactions
- POST /api/wallet/deposit
- POST /api/wallet/withdraw

All amounts are stored as integer MGA units (no floating point money math).

## V2 — 10% Game Commission

The backend now stores:
- gross_amount
- commission_10_percent
- prize_pool
- winner_payout
- admin_revenue

The 10% commission is calculated server-side using integer MGA values.
The game settlement and ledger entries are written atomically.

New endpoints:
- POST /api/games
- POST /api/games/:gameId/finish
- GET /api/games/:gameId/ledger

For real-money operation, provider settlement/webhooks, authentication hardening,
authorization for game settlement, KYC/AML and applicable gaming/payment rules
must be implemented before production.

## V3 — Server-authoritative game engine

The client cannot set dice, current turn, winner, or payout.
- Secure server RNG (`crypto.randomInt`) decides dice.
- Only the current participant can roll.
- Moves are validated server-side.
- Winner is computed from server-side move history.
- Winner payout is executed atomically with game settlement.
- Optimistic `stateVersion` + Serializable transactions reduce race conditions.
- Client-facing `/finish` settlement is removed; settlement occurs only when the server determines a win.

This is a gameplay foundation, not a complete certified Ludo rules engine. Before real-money launch, add a dedicated game service, stronger anti-replay/session controls, fraud monitoring, audit logs, and regulatory/compliance review.

# V4 Security hardening

V4 adds:
- Helmet security headers
- Rate limiting
- 15-minute access tokens + rotating refresh tokens
- Role-aware authorization; game settlement is restricted to GAME_SERVER
- Request IDs
- Audit logs
- Idempotency on money operations
- Serializable DB transactions for wallet/game state transitions
- Server-generated cryptographic dice (`crypto.randomInt`)
- 10% server-side game commission and auditable ledger

Before real-money production: use HTTPS, managed secrets, database backups,
monitoring/alerts, provider webhook signature verification, KYC/AML and
applicable gaming/payment regulations. The V4 move endpoint is deliberately
conservative and should be completed with the exact Ludo board rules/tests
before real-money release.

## V5 — Full authoritative Ludo engine

Added a pure server-side rules engine for 2–4 players:
- four tokens per player
- home state and six-to-enter
- board-relative positions
- final path and exact finish at 57
- safe squares
- captures back to home
- turn enforcement
- extra turn on six or capture
- winner only when all four tokens finish
- stateVersion increments on authoritative state changes
- automated Jest rule tests

The engine is deliberately isolated from HTTP/database code so it can be tested deterministically.
Before real-money production, add official Ludo rules for the exact target ruleset (including any house-rule differences), concurrency/load tests, security review, and legal/payment compliance.

## V5.1 — Ludo Mada Ruleset

The authoritative engine now uses a versioned, immutable `LUDO_MADA_CLASSIC` ruleset:
- 2–4 players
- 4 tokens per player
- roll 6 to leave home
- extra turn on 6
- extra turn on capture
- fixed safe squares
- exact finish required
- winner only after all four tokens finish

The ruleset is attached to the game state so the rules used for a game are explicit and auditable.
For real-money settlement, changing the ruleset requires a new version rather than mutating an active ruleset.

## V6 — Android ↔ Authoritative Game Server API

V6 adds the live game API contract:
- server-side dice
- authoritative state retrieval
- legal-move discovery
- server-side move validation
- optimistic concurrency with `expectedVersion`
- game event persistence model

Android sends intent (`roll` or `tokenIndex`) rather than outcomes. The server remains the source of truth.
This V6 game-state adapter uses an in-memory authoritative session map as the transport layer; for multi-instance production deployment, move state must be persisted/locked in PostgreSQL or a dedicated authoritative game service (e.g. Redis + durable event store) before real-money settlement.

## V6.1 — Durable authoritative game state

V6.1 replaces the in-memory game-state dependency for roll/move commands with:
- PostgreSQL `AuthoritativeGameState` snapshots
- PostgreSQL `GameCommand` idempotency records
- durable `GameEvent` append records
- PostgreSQL `READ COMMITTED` transactions with a per-game `SELECT ... FOR UPDATE` row lock
- state-version conflict detection
- duplicate command replay returning the original result
- bounded retry/backoff for database serialization conflicts

The lock is acquired on the durable `AuthoritativeGameState` row inside the same transaction as the command, game-row update, idempotency record and event append. This serializes commands for the same game across API instances and lets the state survive process restarts. The implementation uses explicit row locking under `READ COMMITTED`; it does **not** set the transaction isolation level to `SERIALIZABLE`. Load-test this exact locking strategy before production scaling.

## V6.2 — Production hardening
Added Prisma migration baseline, integration/concurrency/security test suites,
and a production security/reliability checklist. The TypeScript unit suite runs in CI; PostgreSQL-backed native HTTP/game/economy
scenarios are added to the Release Gate in V6.3. These checks do not replace an
external penetration test or formal security audit.

## V6.3 — Reproducible CI / PostgreSQL test environment

Added:
- PostgreSQL 16 test service
- `.env.test.example`
- Docker Compose test database
- GitHub Actions CI workflow
- `npm run ci`, `npm run test:all`, and `npm run test:native`
- serialized native HTTP/4-player/concurrency/idempotency tests in the Release Gate
- test matrix documentation

The TypeScript unit suite and the native PostgreSQL-backed integration suite
are separate gates. The native suite runs only against the disposable CI
PostgreSQL service after the API is healthy. Passing these CI gates does not
claim that a security audit, external penetration test, legal review, or
production deployment has been completed.

## V6.4 — Load / Security Release Gate

Added a k6 staging load-test scaffold and a concrete release gate covering
automated tests, concurrency, security review, financial settlement safety,
and legal/compliance review. The k6 test is intentionally a safe scaffold and
must not be aimed at production real-money systems.

## V6.5 — Staging deployment package

Added a containerized staging path:
- Node 24 LTS production container
- PostgreSQL 16 staging compose stack
- explicit Prisma migration CLI command (`npm run db:migrate`)
- staging environment template
- staging deployment/rollback runbook
- build/start scripts

The API container starts `node dist/server.js`; it does **not** run schema migrations on startup. Migrations are a separate owner-controlled operation. The Render staging blueprint deliberately keeps automatic deployment off and uses a runtime database role without schema `CREATE`; baseline the existing empty Supabase staging schema only through `docs/PRISMA_STAGING_BASELINE.md`.


## Real-time game updates and AI move hints

The backend exposes these optional, authenticated endpoints:

- `GET /api/games/:gameId/events` opens a Server-Sent Events stream for a game participant. The stream publishes `game-update` events for lobby joins, game start, dice rolls and token moves. Events contain only a reason and version metadata; clients should refetch `GET /api/games/:gameId/state` for the authoritative snapshot.
- `GET /api/games/:gameId/ai-suggestion` returns a deterministic heuristic token index for the current participant after the dice has been rolled. It is read-only and does not perform the move or change wallet/game state. Submit an accepted move through the normal `/move` endpoint with the current `expectedVersion` and required `Idempotency-Key`.

The Android online board now listens for SSE game updates, re-fetches the authenticated state after updates and reconnects when the activity resumes. Its 4-second refresh is retained as a fallback. A manual **Conseil IA** button displays a suggested pawn but never plays automatically; the player confirms using the normal move controls. The current event hub is process-local, limited to 64 streams per game and 4 per user. It is not a cross-instance message bus and does not replay missed events; clients should fetch the current snapshot after connecting or reconnecting. Before horizontal scaling, add and test a shared fanout adapter. PostgreSQL remains the authoritative game-state store.
