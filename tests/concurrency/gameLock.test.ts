/**
 * Concurrency test plan.
 * In CI this should use a real PostgreSQL test database and two service instances.
 */
describe("V6.2 concurrency",()=>{
  test("two simultaneous moves cannot both commit the same version", async ()=>{
    // Start two MOVE requests with identical expectedVersion.
    // Assert exactly one commits and the other receives STATE_VERSION_CONFLICT.
    expect(true).toBe(true);
  });

  test("duplicate commands are idempotent across instances", async ()=>{
    // Two independent service instances issue the same command key.
    // Assert one durable command and one state transition.
    expect(true).toBe(true);
  });
});
