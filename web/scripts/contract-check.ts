/**
 * Dashboard ↔ backend contract check.
 *
 * Calls every endpoint the admin dashboard consumes and validates the real
 * response against the Zod schemas the UI parses with. Catches schema drift
 * without opening a browser.
 *
 * Usage (from web/):
 *   API_BASE=http://127.0.0.1:8000 node --experimental-strip-types scripts/contract-check.ts
 *
 * If the backend database already has users, pass admin credentials:
 *   ADMIN_USER=alice ADMIN_PASS=password123 ...
 * Otherwise the script registers a fresh user, which is admin on an empty DB.
 */
import {
  adminConfigSchema,
  adminSettingsSchema,
  auditLogSchema,
  channelSchema,
  deviceSchema,
  healthSchema,
  listSchema,
  membershipSchema,
  securityEventSchema,
  tokenPairSchema,
  userSchema,
} from "../src/lib/schemas.ts";

const BASE = (process.env.API_BASE ?? "http://127.0.0.1:8000").replace(/\/$/, "");
const ADMIN_USER = process.env.ADMIN_USER ?? `contract_${Date.now().toString(36)}`;
const ADMIN_PASS = process.env.ADMIN_PASS ?? "password123";

type Check = { name: string; ok: boolean; detail?: string };
const checks: Check[] = [];

async function call<T>(
  name: string,
  path: string,
  init: RequestInit,
  schema: { parse: (v: unknown) => T },
): Promise<T | undefined> {
  try {
    const res = await fetch(`${BASE}/api/v1${path}`, init);
    const text = await res.text();
    if (!res.ok) {
      checks.push({ name, ok: false, detail: `HTTP ${res.status} ${text.slice(0, 200)}` });
      return undefined;
    }
    const body = text ? JSON.parse(text) : null;
    schema.parse(body);
    checks.push({ name, ok: true });
    return body as T;
  } catch (err) {
    checks.push({ name, ok: false, detail: err instanceof Error ? err.message : String(err) });
    return undefined;
  }
}

const jsonHeaders = { "Content-Type": "application/json" };

async function main(): Promise<number> {
  // 1. auth: login (existing admin) or register (empty DB ⇒ bootstrap admin)
  let tokens: unknown;
  if (process.env.ADMIN_USER) {
    tokens = await call("POST /auth/login", "/auth/login", {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify({ display_name: ADMIN_USER, password: ADMIN_PASS }),
    }, tokenPairSchema);
  } else {
    tokens = await call("POST /auth/register (bootstrap admin)", "/auth/register", {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify({ display_name: ADMIN_USER, password: ADMIN_PASS }),
    }, tokenPairSchema);
  }
  const pair = tokens as { access_token: string; refresh_token: string } | undefined;
  if (!pair) return report();

  const auth = { Authorization: `Bearer ${pair.access_token}` };
  const authed = { headers: auth };

  await call("GET /health (public, liveness)", "/health", {}, healthSchema);
  await call("POST /auth/refresh", "/auth/refresh", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify({ refresh_token: pair.refresh_token }),
  }, tokenPairSchema);

  const users = await call("GET /admin/users", "/admin/users", authed, listSchema(userSchema));
  await call("GET /admin/channels", "/admin/channels", authed, listSchema(channelSchema));
  await call("GET /admin/security-events", "/admin/security-events", authed, listSchema(securityEventSchema));
  await call("GET /admin/audit", "/admin/audit", authed, listSchema(auditLogSchema));
  await call("GET /admin/health", "/admin/health", authed, healthSchema);
  await call("GET /admin/config", "/admin/config", authed, adminConfigSchema);
  await call("GET /admin/settings", "/admin/settings", authed, adminSettingsSchema);
  await call("GET /admin/memberships", "/admin/memberships", authed, listSchema(membershipSchema));
  await call("GET /admin/devices", "/admin/devices", authed, listSchema(deviceSchema));

  // Mutations used by the UI, reverted afterwards.
  const settings = (await call(
    "PUT /admin/settings (round-trip)",
    "/admin/settings",
    { ...authed, method: "PUT", headers: { ...auth, ...jsonHeaders },
      body: JSON.stringify({ maintenance_mode: false, registration_enabled: true }) },
    adminSettingsSchema,
  )) as Record<string, unknown> | undefined;
  if (settings) checks.push({ name: "settings round-trip values", ok: settings.maintenance_mode === false });

  if (users && users.length > 1) {
    const victim = (users as { id: string; role: string }[]).find((u) => u.role !== "admin");
    if (victim) {
      await call(`POST /admin/users/{id}/disable`, `/admin/users/${victim.id}/disable`, { ...authed, method: "POST" }, userSchema);
      await call(`POST /admin/users/{id}/enable`, `/admin/users/${victim.id}/enable`, { ...authed, method: "POST" }, userSchema);
    }
  }

  // Per-user views used by the dashboard shell.
  await call("GET /devices", "/devices", authed, listSchema(deviceSchema));
  await call("GET /channels", "/channels", authed, listSchema(channelSchema));

  return report();
}

function report(): number {
  const failed = checks.filter((c) => !c.ok);
  console.log(JSON.stringify({ ok: failed.length === 0, checks }, null, 2));
  return failed.length === 0 ? 0 : 1;
}

main()
  .then((code) => {
    // Do not call process.exit() here: Node 24 on Windows aborts in libuv if
    // keep-alive fetch sockets are still open.
    process.exitCode = code;
  })
  .catch((err) => {
    console.error(err);
    process.exitCode = 1;
  });
