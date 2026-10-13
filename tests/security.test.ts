import { clientIp, hashToken, issueAccessToken, verifyAccessToken } from "../src/security";

describe("security helpers", () => {
  test("clientIp uses Express-normalized req.ip", () => {
    const req:any = {
      ip: "198.51.100.17",
      socket: { remoteAddress: "127.0.0.1" },
      headers: { "x-forwarded-for": "203.0.113.99, 198.51.100.2" }
    };
    expect(clientIp(req)).toBe("198.51.100.17");
  });

  test("access tokens require HS256 and valid role claims", () => {
    const secret = "test-secret-abcdefghijklmnopqrstuvwxyz";
    const token = issueAccessToken("user-1", "PLAYER", secret);
    expect(verifyAccessToken(token, secret).sub).toBe("user-1");
  });

  test("token hashes are deterministic", () => {
    expect(hashToken("abc")).toBe(hashToken("abc"));
    expect(hashToken("abc")).not.toBe(hashToken("def"));
  });
});
