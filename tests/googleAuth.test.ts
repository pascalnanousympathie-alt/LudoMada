import { OAuth2Client } from "google-auth-library";
import {
  googleUsername,
  hashGoogleNonce,
  newGoogleNonce,
  validateGooglePayload,
  verifyGoogleIdToken,
  GoogleTokenPayload,
} from "../src/googleAuth";

describe("Google authentication", () => {
  const future = Math.floor(Date.now() / 1000) + 300;
  const validPayload = (overrides: Partial<GoogleTokenPayload> = {}) => ({
    iss: "https://accounts.google.com",
    aud: "web-client-id",
    sub: "google-sub-1",
    email: "person@gmail.com",
    email_verified: true,
    nonce: "nonce-12345678901234567890123456789012",
    exp: future,
    ...overrides,
  });

  test("nonce is random, URL-safe and hashed deterministically", () => {
    const nonce = newGoogleNonce();
    expect(nonce.length).toBeGreaterThan(30);
    expect(nonce).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(hashGoogleNonce(nonce)).toHaveLength(64);
    expect(hashGoogleNonce(nonce)).toBe(hashGoogleNonce(nonce));
    expect(newGoogleNonce()).not.toBe(nonce);
  });

  test("username is deterministic, valid and bounded", () => {
    const name = googleUsername("Jean.Paul+test@gmail.com", "google-sub-123");
    expect(name).toMatch(/^[A-Za-z0-9_]{3,32}$/);
    expect(name).toBe(googleUsername("Jean.Paul+test@gmail.com", "google-sub-123"));
  });

  test("accepts a verified Gmail token with matching issuer, audience, nonce and expiry", () => {
    expect(validateGooglePayload(validPayload(), "web-client-id",
      "nonce-12345678901234567890123456789012")).toMatchObject({
        sub: "google-sub-1",
        email: "person@gmail.com",
      });
  });

  test("accepts a verified Google Workspace identity with hosted domain", () => {
    expect(validateGooglePayload(validPayload({
      email: "employee@example.com",
      hd: "example.com",
    }), "web-client-id", "nonce-12345678901234567890123456789012").hostedDomain)
      .toBe("example.com");
  });

  const invalidClaims: Array<[string, Partial<GoogleTokenPayload>]> = [
    ["issuer", { iss: "https://evil.example" }],
    ["audience", { aud: "another-client" }],
    ["nonce", { nonce: "wrong-nonce" }],
    ["subject", { sub: "" }],
    ["expiry", { exp: Math.floor(Date.now() / 1000) - 1 }],
  ];

  test.each(invalidClaims)("rejects invalid %s claims", (_label, changes) => {
    expect(() => validateGooglePayload(validPayload(changes), "web-client-id",
      "nonce-12345678901234567890123456789012")).toThrow("GOOGLE_TOKEN_INVALID");
  });

  test("rejects unverified email", () => {
    expect(() => validateGooglePayload(validPayload({ email_verified: false }),
      "web-client-id", "nonce-12345678901234567890123456789012"))
      .toThrow("GOOGLE_EMAIL_NOT_VERIFIED");
  });

  test("requires extra verification for unhosted third-party email addresses", () => {
    expect(() => validateGooglePayload(validPayload({
      email: "person@example.net",
    }), "web-client-id", "nonce-12345678901234567890123456789012"))
      .toThrow("GOOGLE_EMAIL_REVERIFICATION_REQUIRED");
  });

  test("uses OAuth2Client.verifyIdToken with the configured audience", async () => {
    const verify = jest.spyOn(OAuth2Client.prototype, "verifyIdToken").mockResolvedValue({
      getPayload: () => validPayload() as any,
    } as any);
    try {
      const result = await verifyGoogleIdToken(
        "x".repeat(200),
        "web-client-id",
        "nonce-12345678901234567890123456789012",
      );
      expect(result.sub).toBe("google-sub-1");
      expect(verify).toHaveBeenCalledWith({
        idToken: "x".repeat(200),
        audience: "web-client-id",
      });
    } finally {
      verify.mockRestore();
    }
  });

  test("fails closed when the Google library rejects the token", async () => {
    const verify = jest.spyOn(OAuth2Client.prototype, "verifyIdToken")
      .mockRejectedValue(new Error("bad signature"));
    try {
      await expect(verifyGoogleIdToken(
        "x".repeat(200),
        "web-client-id",
        "nonce-12345678901234567890123456789012",
      )).rejects.toThrow("GOOGLE_TOKEN_INVALID");
    } finally {
      verify.mockRestore();
    }
  });
});
