import { z } from "zod";
import { tokenPairSchema } from "./schemas";

const BASE = "/api/v1";
const ACCESS_KEY = "securetalk.admin.access";
const REFRESH_KEY = "securetalk.admin.refresh";
/** Emitted when tokens are cleared after a failed refresh; AuthProvider listens. */
export const AUTH_INVALIDATED = "securetalk:auth-invalidated";

export class ApiError extends Error {
  status: number;
  unavailable: boolean;
  constructor(status: number, message: string, unavailable = false) {
    super(message);
    this.status = status;
    this.unavailable = unavailable;
  }
}

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_KEY);
}
export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY);
}
export function storeTokens(access: string, refresh?: string): void {
  localStorage.setItem(ACCESS_KEY, access);
  if (refresh) localStorage.setItem(REFRESH_KEY, refresh);
}
export function clearTokens(): void {
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
}

let refreshing: Promise<boolean> | null = null;

async function tryRefresh(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      const refresh = getRefreshToken();
      if (!refresh) return false;
      try {
        const res = await fetch(`${BASE}/auth/refresh`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refresh_token: refresh }),
        });
        if (!res.ok) return false;
        const pair = tokenPairSchema.parse(await res.json());
        storeTokens(pair.access_token, pair.refresh_token);
        return true;
      } catch {
        return false;
      } finally {
        setTimeout(() => (refreshing = null), 0);
      }
    })();
  }
  return refreshing;
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Skip automatic 401 refresh/retry (used by login itself). */
  noAuth?: boolean;
}

/**
 * Authenticated API call. On 401, attempts one token refresh then retries.
 * Response JSON is validated by the caller-provided Zod schema.
 */
export async function api<T>(
  path: string,
  schema: z.ZodType<T>,
  opts: RequestOptions = {},
): Promise<T> {
  const doFetch = (token: string | null) =>
    fetch(`${BASE}${path}`, {
      method: opts.method ?? "GET",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    });

  let res = await doFetch(opts.noAuth ? null : getAccessToken());

  if (res.status === 401 && !opts.noAuth) {
    const ok = await tryRefresh();
    if (ok) {
      res = await doFetch(getAccessToken());
    } else {
      clearTokens();
      window.dispatchEvent(new Event(AUTH_INVALIDATED));
      throw new ApiError(401, "Session expired. Please sign in again.");
    }
  }

  if (res.status === 204) {
    return undefined as T;
  }

  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const data = (await res.json()) as { detail?: unknown };
      if (typeof data.detail === "string") message = data.detail;
    } catch {
      /* keep default message */
    }
    throw new ApiError(res.status, message, res.status === 404 || res.status >= 500);
  }

  let json: unknown;
  try {
    json = await res.json();
  } catch {
    throw new ApiError(res.status, "Response was not valid JSON", true);
  }
  const parsed = schema.safeParse(json);
  if (!parsed.success) {
    throw new ApiError(res.status, "Response did not match the expected API schema", true);
  }
  return parsed.data;
}

/**
 * Public endpoint call without the /api/v1 prefix (e.g. GET /health).
 * No auth header, no refresh retry — liveness probes must never mutate session state.
 */
export async function apiPublic<T>(path: string, schema: z.ZodType<T>): Promise<T> {
  const res = await fetch(path, { headers: { Accept: "application/json" } });
  if (!res.ok) throw new ApiError(res.status, `Request failed (${res.status})`);
  if (res.status === 204) return undefined as T;
  const parsed = schema.safeParse(await res.json());
  if (!parsed.success) throw new ApiError(res.status, "Response did not match the expected API schema", true);
  return parsed.data;
}

export async function login(displayName: string, password: string): Promise<void> {
  const pair = await api(
    "/auth/login",
    tokenPairSchema,
    { method: "POST", body: { display_name: displayName, password }, noAuth: true },
  );
  storeTokens(pair.access_token, pair.refresh_token);
}

export async function logout(): Promise<void> {
  const refresh = getRefreshToken();
  try {
    // Backend requires the refresh token in the body; otherwise 422 and the
    // server-side session (refresh jti) would remain valid after local logout.
    if (refresh) {
      await api("/auth/logout", z.unknown(), {
        method: "POST",
        body: { refresh_token: refresh },
        noAuth: true,
      });
    }
  } catch {
    /* best effort */
  }
  clearTokens();
}
