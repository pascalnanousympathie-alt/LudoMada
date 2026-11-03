/**
 * Native Android clients do not send an Origin header and are not subject to
 * browser CORS. When no browser origins are configured, deny all browser
 * cross-origin requests rather than making the API fail to start.
 */
export function isCorsOriginAllowed(
  origin: string | undefined,
  allowedOrigins: readonly string[]
): boolean {
  if (!origin) return true;
  return allowedOrigins.includes(origin);
}
