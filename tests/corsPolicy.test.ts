import { isCorsOriginAllowed } from "../src/corsPolicy";

describe("isCorsOriginAllowed", () => {
  it("allows native requests that do not send an Origin header", () => {
    expect(isCorsOriginAllowed(undefined, [])).toBe(true);
  });

  it("denies every browser origin when the allowlist is empty", () => {
    expect(isCorsOriginAllowed("https://example.invalid", [])).toBe(false);
  });

  it("allows an exact configured browser origin", () => {
    expect(isCorsOriginAllowed("https://ludomada.example", ["https://ludomada.example"])).toBe(true);
  });

  it("rejects a different scheme, host, port or trailing slash", () => {
    const allowed = ["https://ludomada.example"];
    expect(isCorsOriginAllowed("http://ludomada.example", allowed)).toBe(false);
    expect(isCorsOriginAllowed("https://sub.ludomada.example", allowed)).toBe(false);
    expect(isCorsOriginAllowed("https://ludomada.example:8443", allowed)).toBe(false);
    expect(isCorsOriginAllowed("https://ludomada.example/", allowed)).toBe(false);
  });

  it("does not treat an asterisk as a wildcard", () => {
    expect(isCorsOriginAllowed("https://attacker.invalid", ["*"])).toBe(false);
  });
});
