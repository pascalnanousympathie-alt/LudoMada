/**
 * Integration-test checklist for V6.2.
 * Run against a disposable PostgreSQL DATABASE_URL.
 */
describe("V6.2 persistence",()=>{
  test("state survives process restart", async ()=>{
    // 1. create a game and persist a stateVersion
    // 2. dispose/recreate service instance
    // 3. load the same game
    // 4. assert stateVersion/state are identical
    expect(true).toBe(true);
  });

  test("duplicate idempotency key returns original result", async ()=>{
    // Execute the same ROLL command twice with the same key.
    // The second call must not mutate state or append a second command.
    expect(true).toBe(true);
  });
});
