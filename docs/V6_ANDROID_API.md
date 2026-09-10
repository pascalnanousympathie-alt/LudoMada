# Ludo Mada V6 Android API

All game-state-changing requests use HTTPS + JWT.

- `POST /api/games/:gameId/roll`
- `GET /api/games/:gameId/state`
- `GET /api/games/:gameId/legal-moves`
- `POST /api/games/:gameId/move`

Move body:
`{"tokenIndex":0,"expectedVersion":12}`

The client never supplies dice results or winner results.
A stale `expectedVersion` receives `STATE_VERSION_CONFLICT`; Android must refresh state and retry only when the user explicitly performs the move again.
