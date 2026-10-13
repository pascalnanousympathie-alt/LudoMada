import crypto from "node:crypto";

export type GoogleIdentity = {
  sub: string;
  email: string;
  name?: string;
  picture?: string;
};

export type GoogleTokenPayload = {
  iss?: string;
  aud?: string | string[];
  sub?: string;
  email?: string;
  email_verified?: boolean | string;
  nonce?: string;
  name?: string;
  picture?: string;
};

const GOOGLE_TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";
const GOOGLE_ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);

export function newGoogleNonce(): string {
  return crypto.randomBytes(32).toString("base64url");
}

export function hashGoogleNonce(nonce: string): string {
  return crypto.createHash("sha256").update(nonce).digest("hex");
}

export function googleUsername(email: string, sub: string): string {
  const local = email.split("@")[0].replace(/[^A-Za-z0-9_]/g, "_").slice(0, 20) || "google_user";
  return (local + "_" + crypto.createHash("sha256").update(sub).digest("hex").slice(0, 8)).slice(0, 32);
}

export function validateGooglePayload(payload: GoogleTokenPayload, audience: string, expectedNonce: string): GoogleIdentity {
  const issuerOk = GOOGLE_ISSUERS.has(String(payload.iss || ""));
  const audienceOk = Array.isArray(payload.aud) ? payload.aud.includes(audience) : payload.aud === audience;
  const emailVerified = payload.email_verified === true || payload.email_verified === "true";
  if(!issuerOk || !audienceOk || payload.nonce !== expectedNonce || !payload.sub){
    throw new Error("GOOGLE_TOKEN_INVALID");
  }
  if(!payload.email || !emailVerified) throw new Error("GOOGLE_EMAIL_NOT_VERIFIED");
  return {
    sub: payload.sub,
    email: payload.email.trim().toLowerCase(),
    name: payload.name,
    picture: payload.picture
  };
}

export async function verifyGoogleIdToken(idToken: string, audience: string, expectedNonce: string): Promise<GoogleIdentity> {
  const url = new URL(GOOGLE_TOKENINFO_URL);
  url.searchParams.set("id_token", idToken);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);
  try{
    const response = await fetch(url, { method: "GET", signal: controller.signal, headers: { accept: "application/json" } });
    if(!response.ok) throw new Error("GOOGLE_TOKEN_INVALID");
    const payload = await response.json() as GoogleTokenPayload;
    return validateGooglePayload(payload, audience, expectedNonce);
  }catch(error:any){
    if(error?.message === "GOOGLE_EMAIL_NOT_VERIFIED" || error?.message === "GOOGLE_TOKEN_INVALID") throw error;
    throw new Error("GOOGLE_TOKEN_INVALID");
  }finally{
    clearTimeout(timeout);
  }
}
