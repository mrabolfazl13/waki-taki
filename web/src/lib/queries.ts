import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { api, ApiError } from "./api";
import {
  auditLogSchema,
  channelSchema,
  healthSchema,
  listSchema,
  securityEventSchema,
  userSchema,
  type AuditLog,
  type Channel,
  type HealthReport,
  type SecurityEvent,
  type User,
} from "./schemas";

export function useUsers() {
  return useQuery<User[], ApiError>({
    queryKey: ["admin", "users"],
    queryFn: () => api("/admin/users", listSchema(userSchema)),
  });
}

export function useSetUserDisabled() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, disabled }: { id: string; disabled: boolean }) =>
      api(`/admin/users/${id}/disable`, z.unknown(), { method: "POST", body: { disabled } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin", "users"] }),
  });
}

export function useChannels() {
  return useQuery<Channel[], ApiError>({
    queryKey: ["admin", "channels"],
    queryFn: () => api("/admin/channels", listSchema(channelSchema)),
  });
}

export function useDeleteChannel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      api(`/admin/channels/${id}`, z.unknown(), { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin", "channels"] }),
  });
}

export function useSecurityEvents() {
  return useQuery<SecurityEvent[], ApiError>({
    queryKey: ["admin", "security-events"],
    queryFn: () => api("/admin/security-events", listSchema(securityEventSchema)),
  });
}

export function useAuditLogs() {
  return useQuery<AuditLog[], ApiError>({
    queryKey: ["admin", "audit"],
    queryFn: () => api("/admin/audit", listSchema(auditLogSchema)),
  });
}

export function useAdminHealth() {
  return useQuery<HealthReport, ApiError>({
    queryKey: ["admin", "health"],
    queryFn: () => api("/admin/health", healthSchema),
    refetchInterval: 15000,
  });
}

/** Endpoints that exist in the admin surface but may not be implemented yet. */
export function useAdminList<T>(key: string, path: string, item: z.ZodType<T>) {
  return useQuery<T[], ApiError>({
    queryKey: ["admin", key],
    queryFn: () => api(path, listSchema(item)),
    retry: false,
  });
}
