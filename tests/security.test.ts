import { createSecretKey } from "node:crypto";
import jwt from "jsonwebtoken";
import { issueAccessToken, verifyAccessToken } from "../src/security";

const secret = "ludomada-test-hmac-secret-at-least-32-chars";
const verificationKey = createSecretKey(Buffer.from(secret, "utf8"));

describe("JWT access-token verification", () => {
  test("verifies an HS256 access token with a pre-parsed SecretKeyObject", () => {
    const token = issueAccessToken("player-1", "PLAYER", secret);

    const claims = verifyAccessToken(token, verificationKey);

    expect(claims.sub).toBe("player-1");
    expect(claims.role).toBe("PLAYER");
    expect(claims.jti).toEqual(expect.any(String));
    expect(claims.jti.length).toBeGreaterThan(0);
  });

  test("preserves compatibility with a string HMAC secret", () => {
    const token = issueAccessToken("player-2", "ADMIN", secret);

    expect(verifyAccessToken(token, secret)).toEqual(
      verifyAccessToken(token, verificationKey)
    );
  });

  test("rejects an access token signed by a different HMAC key", () => {
    const token = issueAccessToken("player-3", "PLAYER", secret);
    const wrongKey = createSecretKey(Buffer.from(
      "different-ludomada-test-secret-at-least-32-chars",
      "utf8"
    ));

    expect(() => verifyAccessToken(token, wrongKey)).toThrow();
  });

  test("keeps the HS256-only algorithm allowlist", () => {
    const token = jwt.sign(
      { sub: "player-4", role: "PLAYER", jti: "test-jti" },
      secret,
      { algorithm: "HS384", expiresIn: "15m" }
    );

    expect(() => verifyAccessToken(token, verificationKey)).toThrow(
      "invalid algorithm"
    );
  });

  test("rejects tokens with invalid application claims", () => {
    const token = jwt.sign(
      { sub: "player-5", role: "ROOT", jti: "test-jti" },
      secret,
      { algorithm: "HS256", expiresIn: "15m" }
    );

    expect(() => verifyAccessToken(token, verificationKey)).toThrow(
      "INVALID_TOKEN_CLAIMS"
    );
  });
});
