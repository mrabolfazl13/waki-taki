import { z } from "zod";

/**
 * Zod schemas for the SecureTalk /api/v1 contract (ARCHITECTURE.md §5).
 * The backend is under concurrent development, so list endpoints accept
 * either a bare array or an `{items: [...]}` envelope, and optional
 * fields are marked optional rather than invented.
 */

export const tokenPairSchema = z.object({
  access_token: z.string().min(1),
  refresh_token: z.string().min(1).optional(),
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
  owner_id: z.string().optional(),
  created_at: z.string().optional(),
  member_count: z.number().int().nonnegative().optional(),
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
  user_id: z.string(),
  role: z.string().optional(),
  joined_at: z.string().optional(),
});
export type Membership = z.infer<typeof membershipSchema>;

export const adminRoleSchema = z.object({
  id: z.string().optional(),
  name: z.string(),
  permissions: z.array(z.string()).optional(),
});
export type AdminRole = z.infer<typeof adminRoleSchema>;

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
