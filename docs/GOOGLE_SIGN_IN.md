# Google Sign-In configuration

LudoMada uses the Android Credential Manager to obtain a Google ID token. The backend verifies the signed token locally with Google's official Node.js google-auth-library; it does not use the tokeninfo debugging endpoint in production.

## Google Cloud setup

1. Create an OAuth consent screen and configure the Android application ID com.ludomadagascar.
2. Create an Android OAuth client for the release package and its SHA-1 signing certificate fingerprint(s). Add the debug fingerprint separately for emulator/debug builds.

   - Verified release signer for Release Gate run `41877433902` (commit `460b6561e0a9d7d0ee840b95903651cc6b924e2b`): `C2:A2:F7:F9:C4:3E:B7:55:F7:D3:61:BC:E3:4A:2F:B7:ED:B7:9E:D3`.
   - This SHA-1 was read from the signed release APK and verified with APK Signature Scheme v2. Re-check the actual signed APK whenever the release keystore is rotated. Do not register a CI-generated debug fingerprint as the production release signer.
3. Create a Web application OAuth client. This Web client ID is the server audience used by Android Credential Manager and the backend.
4. Configure the Web client ID as GOOGLE_WEB_CLIENT_ID in the backend's protected environment settings. It is a public OAuth identifier, not a client secret. Do not put a Google client secret in the Android app.

## Runtime flow

- POST /api/auth/google/challenge creates a random, short-lived nonce and stores only its SHA-256 hash.
- Android passes that nonce to Google Credential Manager and submits the resulting ID token plus nonce over HTTPS.
- The backend verifies signature, issuer, audience and expiry with OAuth2Client.verifyIdToken, then checks the signed nonce and email claims.
- The challenge is atomically consumed once. Existing password accounts are not silently linked to a Google identity; a matching email returns 409 GOOGLE_EMAIL_ALREADY_REGISTERED.
- New passwordless accounts are restricted to Gmail or verified Google Workspace hosted-domain identities. Other third-party email addresses need an additional verification/linking flow.

## Deployment gate

The API deliberately returns 503 GOOGLE_AUTH_NOT_CONFIGURED when GOOGLE_WEB_CLIENT_ID is missing. Configure the same Web client ID in the actual backend service before attempting a real-device login. The staging database must receive the Prisma migration 20261030080000_add_google_signin through the deployment's reviewed migration procedure before enabling this endpoint. Do not apply it manually to a database with a different migration baseline.
