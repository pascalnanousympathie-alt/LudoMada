import crypto from "node:crypto";
import { OAuth2Client } from "google-auth-library";

export type GoogleIdentity = {
  sub: string;
  email: string;
  hostedDomain?: string;
  name?: string;
  picture?: string;
};

export type GoogleTokenPayload = {
  iss?: string;
  aud?: string | string[];
  sub?: string;
  email?: string;
  email_verified?: boolean | string;
  exp?: number;
  nonce?: string;
  hd?: string;
  name?: string;
  picture?: string;
};

const GOOGLE_ISSUERS = new Set([
  "accounts.google.com",
  "https://accounts.google.com",
]);
const googleOAuthClient = new OAuth2Client();

export function newGoogleNonce(): string {
  return crypto.randomBytes(32).toString("base64url");
}

export function hashGoogleNonce(nonce: string): string {
  return crypto.createHash("sha256").update(nonce).digest("hex");
}

export function googleUsername(email: string, sub: string): string {
  const local = email.split("@")[0]
    .replace(/[^A-Za-z0-9_]/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 20) || "google_user";
  const suffix = crypto.createHash("sha256").update(sub).digest("hex").slice(0, 10);
  return (local + "_" + suffix).slice(0, 32);
}

/**
 * Google Auth Library validates signature, issuer, audience and expiry.
 * These additional checks bind the signed identity to our one-time nonce.
 */
export function validateGooglePayload(
  payload: GoogleTokenPayload,
  audience: string,
  expectedNonce: string
): GoogleIdentity {
  const issuerOk = GOOGLE_ISSUERS.has(String(payload.iss || ""));
  const audienceOk = payload.aud === audience ||
    (Array.isArray(payload.aud) && payload.aud.includes(audience));
  const email = typeof payload.email === "string"
    ? payload.email.trim().toLowerCase()
    : "";
  const emailVerified = payload.email_verified === true ||
    payload.email_verified === "true";

  if (
    !issuerOk ||
    !audienceOk ||
    !payload.sub ||
    typeof payload.exp !== "number" ||
    payload.exp <= Math.floor(Date.now() / 1000) ||
    payload.nonce !== expectedNonce
  ) {
    throw new Error("GOOGLE_TOKEN_INVALID");
  }
  if (!email || !emailVerified) {
    throw new Error("GOOGLE_EMAIL_NOT_VERIFIED");
  }

  // Google remains authoritative for Gmail and hosted Workspace identities.
  // For third-party addresses without hd, require a separate email verification.
  const hostedDomain = typeof payload.hd === "string" ? payload.hd.trim().toLowerCase() : "";
  if (!email.endsWith("@gmail.com") && !hostedDomain) {
    throw new Error("GOOGLE_EMAIL_REVERIFICATION_REQUIRED");
  }
  if (hostedDomain && !email.endsWith("@" + hostedDomain)) {
    throw new Error("GOOGLE_TOKEN_INVALID");
  }

  return {
    sub: payload.sub,
    email,
    hostedDomain: hostedDomain || undefined,
    name: typeof payload.name === "string" ? payload.name : undefined,
    picture: typeof payload.picture === "string" ? payload.picture : undefined,
  };
}

export async function verifyGoogleIdToken(
  idToken: string,
  audience: string,
  expectedNonce: string
): Promise<GoogleIdentity> {
  if (!idToken || idToken.length < 100 || idToken.length > 10000) {
    throw new Error("GOOGLE_TOKEN_INVALID");
  }
  if (!audience.trim() || !expectedNonce || expectedNonce.length < 32) {
    throw new Error("GOOGLE_AUTH_CONFIGURATION_INVALID");
  }

  let payload: GoogleTokenPayload;
  try {
    const ticket = await googleOAuthClient.verifyIdToken({
      idToken,
      audience,
    });
    const verified = ticket.getPayload();
    if (!verified) throw new Error("GOOGLE_TOKEN_INVALID");
    payload = verified as GoogleTokenPayload;
  } catch {
    throw new Error("GOOGLE_TOKEN_INVALID");
  }

  return validateGooglePayload(payload, audience, expectedNonce);
}
