import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { api, ApiError } from "../lib/api";
import { useAdminList } from "../lib/queries";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Badge } from "../components/ui/badge";
import { EmptyState, ErrorState, LoadingState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";

const inviteSchema = z.object({
  id: z.string(),
  created_by: z.string(),
  created_at: z.string(),
  expires_at: z.string(),
  used_by: z.string().nullable().optional(),
  used_at: z.string().nullable().optional(),
});
type Invite = z.infer<typeof inviteSchema>;

const mintSchema = inviteSchema.extend({ code: z.string() });

function statusOf(i: Invite): { label: string; tone: "default" | "success" | "destructive" } {
  if (i.used_by) return { label: "used", tone: "default" };
  if (new Date(i.expires_at).getTime() < Date.now()) return { label: "expired", tone: "destructive" };
  return { label: "unused", tone: "success" };
}

/** Single-use signup invite codes: mint once, copy the raw code, hand it to the user. */
export default function InvitesPage() {
  const invites = useAdminList<Invite>("signup-invites", "/admin/invites", inviteSchema);
  const queryClient = useQueryClient();
  const [minted, setMinted] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const mint = useMutation({
    mutationFn: async () =>
      api("/admin/invites", mintSchema, { method: "POST", body: { ttl_hours: 72 } }),
    onSuccess: (created) => {
      setMinted(created.code);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ["admin", "signup-invites"] });
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : String(e)),
  });

  const revoke = useMutation({
    mutationFn: async (id: string) => api(`/admin/invites/${id}`, z.any(), { method: "DELETE" }),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["admin", "signup-invites"] }),
    onError: (e) => setError(e instanceof ApiError ? e.message : String(e)),
  });

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Signup Invites</h1>
        <p className="text-sm text-muted-foreground">
          Single-use codes for registering accounts. When the server enforces invites, a new user can only
          register with a valid unused code.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Create invite code</CardTitle>
          <CardDescription>The raw code is shown only once — copy it now.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <Button onClick={() => mint.mutate()} disabled={mint.isPending}>
            {mint.isPending ? "Creating…" : "Create single-use invite"}
          </Button>
          {minted && (
            <div className="flex flex-wrap items-center gap-3 rounded-md border p-3">
              <code className="font-mono text-lg font-semibold tracking-widest">{minted}</code>
              <Button
                variant="outline"
                onClick={() => void navigator.clipboard.writeText(minted)}
              >
                Copy
              </Button>
            </div>
          )}
          {error && <p className="text-sm text-destructive">{error}</p>}
        </CardContent>
      </Card>

      {invites.isPending ? (
        <LoadingState />
      ) : invites.isError ? (
        <ErrorState message={invites.error.message} onRetry={() => invites.refetch()} />
      ) : (invites.data?.length ?? 0) === 0 ? (
        <EmptyState
          title="No invites"
          description="Create a code above and share it with the person you want to invite."
        />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Invite history</CardTitle>
            <CardDescription>{invites.data?.length} codes</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Status</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead>Expires</TableHead>
                  <TableHead>Used by</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {invites.data?.map((i) => {
                  const st = statusOf(i);
                  return (
                    <TableRow key={i.id}>
                      <TableCell>
                        <Badge variant={st.tone === "success" ? "default" : st.tone === "destructive" ? "destructive" : "secondary"}>
                          {st.label}
                        </Badge>
                      </TableCell>
                      <TableCell>{new Date(i.created_at).toLocaleString()}</TableCell>
                      <TableCell>{new Date(i.expires_at).toLocaleString()}</TableCell>
                      <TableCell>{i.used_by ?? "—"}</TableCell>
                      <TableCell className="text-right">
                        {!i.used_by && (
                          <Button variant="outline" size="sm" onClick={() => revoke.mutate(i.id)} disabled={revoke.isPending}>
                            Revoke
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
