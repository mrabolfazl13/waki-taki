import { z } from "zod";

/**
 * Zod schemas for the SecureTalk /api/v1 contract (ARCHITECTURE.md §5).
 * The backend is under concurrent development, so list endpoints accept
 * either a bare array or an `{items: [...]}` envelope, and optional
 * fields are marked optional rather than invented.
 */

export const tokenPairSchema = z.object({
  access_token: z.string().min(1),
  // Backend always returns a refresh token alongside the access token.
  refresh_token: z.string().min(1),
  token_type: z.string().optional(),
});
export type TokenPair = z.infer<typeof tokenPairSchema>;

export const userSchema = z.object({
  id: z.string(),
  display_name: z.string(),
  role: z.string(),
  disabled: z.boolean().optional(),
  created_at: z.string().optional(),
});
export type User = z.infer<typeof userSchema>;

export const channelSchema = z.object({
  id: z.string(),
  name: z.string(),
  // /admin/channels includes owner_id + created_at; /channels (per-user) does not.
  owner_id: z.string().optional(),
  created_at: z.string().optional(),
  epoch: z.number().int().positive().optional(),
  member_count: z.number().int().nonnegative().optional(),
  is_owner: z.boolean().optional(),
});
export type Channel = z.infer<typeof channelSchema>;

export const deviceSchema = z.object({
  id: z.string(),
  user_id: z.string().optional(),
  name: z.string().optional(),
  registered_at: z.string().optional(),
  revoked_at: z.string().nullable().optional(),
});
export type Device = z.infer<typeof deviceSchema>;

export const membershipSchema = z.object({
  channel_id: z.string(),
  channel_name: z.string().optional(),
  user_id: z.string(),
  user_display_name: z.string().optional(),
  role: z.string().optional(),
  joined_at: z.string().optional(),
});
export type Membership = z.infer<typeof membershipSchema>;

/**
 * POST /admin/roles takes {user_id, role: "user"|"admin"} and returns the
 * updated user (UserOut). There is no GET /admin/roles endpoint in Phase 1.
 */
export const adminRoleInputSchema = z.object({
  user_id: z.string().min(1),
  role: z.enum(["user", "admin"]),
});
export type AdminRoleInput = z.infer<typeof adminRoleInputSchema>;
export const adminRoleSchema = userSchema;

export const securityEventSchema = z.object({
  id: z.string(),
  type: z.string(),
  severity: z.string(),
  channel_id: z.string().nullable().optional(),
  user_id: z.string().nullable().optional(),
  detail: z.unknown().optional(),
  created_at: z.string().optional(),
});
export type SecurityEvent = z.infer<typeof securityEventSchema>;

export const auditLogSchema = z.object({
  id: z.string(),
  actor_admin_id: z.string().nullable().optional(),
  action: z.string(),
  target: z.string().nullable().optional(),
  detail: z.unknown().optional(),
  created_at: z.string().optional(),
});
export type AuditLog = z.infer<typeof auditLogSchema>;

/** /admin/health: metric map. Keys are backend-defined; values are scalars. */
export const healthSchema = z.record(z.union([z.string(), z.number(), z.boolean(), z.null()]));
export type HealthReport = z.infer<typeof healthSchema>;

/**
 * GET /admin/config: non-sensitive operational configuration (rate limits,
 * channel cap, token TTLs, floor parameters). Keys are backend-defined scalars;
 * the backend never includes secrets in this response.
 */
export const adminConfigSchema = z.record(
  z.union([z.string(), z.number(), z.boolean(), z.null()]),
);
export type AdminConfig = z.infer<typeof adminConfigSchema>;

/**
 * GET/PUT /admin/settings: allow-listed operational toggles persisted by the
 * backend (defaults merged with overrides). PUT accepts a subset of these keys;
 * unknown keys and non-boolean values are rejected by the backend (422).
 */
export const adminSettingsSchema = z.object({
  registration_enabled: z.boolean(),
  maintenance_mode: z.boolean(),
  invite_creation_enabled: z.boolean(),
});
export type AdminSettings = z.infer<typeof adminSettingsSchema>;
export const adminSettingsInputSchema = adminSettingsSchema.partial();
export type AdminSettingsInput = z.infer<typeof adminSettingsInputSchema>;

/** Accept either a bare array or an {items: [...]} envelope. */
export function listSchema<T>(item: z.ZodType<T>): z.ZodType<T[]> {
  return z.preprocess(
    (v) => {
      if (Array.isArray(v)) return v;
      if (v && typeof v === "object" && "items" in v) return (v as { items: unknown }).items;
      return undefined;
    },
    z.array(item),
  ) as unknown as z.ZodType<T[]>;
}
