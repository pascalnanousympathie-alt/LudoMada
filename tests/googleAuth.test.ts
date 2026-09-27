import { googleUsername, hashGoogleNonce, newGoogleNonce, validateGooglePayload } from "../src/googleAuth";

describe("Google authentication helpers", () => {
  test("nonce is strong, URL-safe, and hashes deterministically", () => {
    const nonce = newGoogleNonce();
    expect(nonce.length).toBeGreaterThan(30);
    expect(nonce).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(hashGoogleNonce(nonce)).toHaveLength(64);
    expect(hashGoogleNonce(nonce)).toBe(hashGoogleNonce(nonce));
  });

  test("username is deterministic and compatible with local username rules", () => {
    const username = googleUsername("Jean.Paul+test@example.com", "google-sub-123");
    expect(username).toMatch(/^[A-Za-z0-9_]{3,32}$/);
    expect(username).toBe(googleUsername("Jean.Paul+test@example.com", "google-sub-123"));
  });

  test("accepts a verified token payload with matching audience, issuer, and nonce", () => {
    expect(validateGooglePayload({
      iss: "https://accounts.google.com",
      aud: "web-client-id",
      sub: "google-sub-1",
      email: "USER@Example.com",
      email_verified: true,
      nonce: "nonce-123",
      name: "User"
    }, "web-client-id", "nonce-123")).toEqual({
      sub: "google-sub-1",
      email: "user@example.com",
      name: "User",
      picture: undefined
    });
  });

  test("accepts array audiences and string email_verified from tokeninfo", () => {
    expect(validateGooglePayload({
      iss: "accounts.google.com",
      aud: ["other-client", "web-client-id"],
      sub: "google-sub-2",
      email: "two@example.com",
      email_verified: "true",
      nonce: "nonce-456"
    }, "web-client-id", "nonce-456").email).toBe("two@example.com");
  });

  test("rejects mismatched nonce and unverified email", () => {
    expect(() => validateGooglePayload({
      iss: "accounts.google.com", aud: "web", sub: "s", email: "a@b.com", email_verified: true, nonce: "wrong"
    }, "web", "expected")).toThrow("GOOGLE_TOKEN_INVALID");
    expect(() => validateGooglePayload({
      iss: "accounts.google.com", aud: "web", sub: "s", email: "a@b.com", email_verified: false, nonce: "expected"
    }, "web", "expected")).toThrow("GOOGLE_EMAIL_NOT_VERIFIED");
  });
});
