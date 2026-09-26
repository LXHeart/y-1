// url-policy.ts — C107-05 (task-107) outbound URL policy for media fetch.
//
// Every URL that leaves the broker for fetch/prepare-fetch passes through
// assertFetchableUrl first, and every redirect hop is re-checked with the same
// policy (checkRedirectLocation). No string building, no shell, no path
// parameters into local filenames — the caller names the output file.

export class UrlPolicyError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "UrlPolicyError";
  }
}

const allowedPorts = new Set(["", "80", "443"]);

/** Reject private/loopback/link-local/CGNAT IP literals (v4, v6, v4-mapped). */
function isPrivateHost(hostname: string): boolean {
  const bare = hostname.replace(/^\[|\]$/g, "");
  if (bare.toLowerCase() === "localhost") return true;
  if (bare.endsWith(".localhost") || bare.endsWith(".local") || bare.endsWith(".internal")
    || bare.endsWith(".home.arpa")) {
    return true;
  }
  const v4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(bare);
  if (v4 !== null) {
    const octets = v4.slice(1).map((part) => Number(part));
    if (octets.some((octet) => octet > 255)) return true; // malformed numeric host — refuse
    const [a, b] = octets as [number, number, number, number];
    if (a === 0 || a === 10 || a === 127) return true;
    if (a === 100 && b >= 64 && b <= 127) return true;
    if (a === 169 && b === 254) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 198 && (b === 18 || b === 19)) return true;
    if (a >= 224) return true; // multicast/reserved
    return false;
  }
  const lower = bare.toLowerCase();
  if (lower === "::" || lower === "::1") return true;
  if (lower.startsWith("::ffff:")) return isPrivateHost(lower.slice(7));
  const firstHex = /^([0-9a-f]{1,4})/u.exec(lower);
  if (firstHex !== null) {
    const value = Number.parseInt(firstHex[1]!, 16);
    if ((value & 0xfe00) === 0xfc00) return true; // fc00::/7 unique local
    if ((value & 0xffc0) === 0xfe80) return true; // fe80::/10 link local
    if ((value & 0xff00) === 0xff00) return true; // multicast
  }
  return false;
}

/**
 * A URL the broker may fetch: http(s) only, no credentials, public host,
 * default ports only. Returns the normalized URL (no fragment, no trailing
 * whitespace tricks). Throws UrlPolicyError with a specific code otherwise.
 */
export function assertFetchableUrl(raw: string): URL {
  const trimmed = raw.trim();
  if (trimmed.length === 0 || /[\s\u0000-\u001f]/u.test(trimmed)) {
    throw new UrlPolicyError("invalid_url", "URL is empty or contains control characters");
  }
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    throw new UrlPolicyError("invalid_url", "URL does not parse");
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new UrlPolicyError("scheme_not_allowed", `scheme ${parsed.protocol} is not http(s)`);
  }
  if (parsed.username !== "" || parsed.password !== "") {
    throw new UrlPolicyError("credentials_not_allowed", "user info in the URL is not allowed");
  }
  if (!allowedPorts.has(parsed.port)) {
    throw new UrlPolicyError("port_not_allowed", `port ${parsed.port} is not 80/443`);
  }
  if (parsed.hostname.length === 0) {
    throw new UrlPolicyError("invalid_url", "URL has no host");
  }
  if (isPrivateHost(parsed.hostname)) {
    throw new UrlPolicyError("private_address", `${parsed.hostname} is not a public address`);
  }
  parsed.hash = "";
  return parsed;
}

/** A redirect hop must satisfy the same policy as the initial request. */
export function checkRedirectLocation(location: string): URL {
  return assertFetchableUrl(location);
}

/**
 * Server-controlled output file name for a fetch: a fixed directory plus a
 * caller-supplied bare name. No path parameters, no traversal — any separator
 * or dot-segment in the name is rejected.
 */
export function safeFetchTargetName(name: string): string {
  if (name.length === 0 || name.length > 128) {
    throw new UrlPolicyError("invalid_target", "target name must be 1-128 characters");
  }
  if (name !== name.trim() || /[\\/\u0000-\u001f]/u.test(name) || name === "." || name === ".."
    || name.startsWith(".")) {
    throw new UrlPolicyError("invalid_target", `target name ${JSON.stringify(name)} is not a bare file name`);
  }
  return name;
}
